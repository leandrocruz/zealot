package zealot.http

import java.net.URI

object curl {

  import better.files.*
  import zealot.commons.*
  import zealot.commons.Outcome.*
  import zealot.http.FormEncoding.*
  import zio.*

  import java.net.URLDecoder
  import java.nio.charset.Charset
  import java.time.ZonedDateTime
  import scala.sys.process.*
  import scala.util.{Failure, Success, Try}

  object CurlHttpEngine {
    def layer = ZLayer {
      for {
        counter <- Ref.make(0)
      } yield CurlHttpEngine(counter)
    }
  }

  /*
   * See https://ec.haxx.se/index.html
   */
  case class CurlHttpEngine(counter: Ref[Int]) extends HttpEngine {

    private val traceRegex = """(.*?)\((.*?):([^:]*?)\)""".r

    override def execute(request: ExecutableHttpRequest)(using ctx: HttpContext, session: HttpSession, trace: Trace): ZLT[HttpResponse] = {

      def onError(msg: String)(cause: Throwable) = BotError(outcome = HttpError, explanation = msg, cause = Some(cause))

      def targetUrl = {
        val value = request.url.toLowerCase
        if      (value.startsWith("http") || value.startsWith("https")) request.url
        else if (value.startsWith("/"))                                 session.baseUrl + request.url
        else                                                            session.baseUrl + "/" + request.url
      }

      def getUrl: String = {
        session.environment match
          case Production => targetUrl
          case Test(host, port, scenario) => s"http://$host:$port/$scenario"
      }

      def build(count: Int, url: String, headersFile: File, bodyFile: File): Task[Seq[String]] = {

        def curl: Seq[String] = Seq("curl", "-k")

        def version: Seq[String] = {

          def from(ver: HttpVersion): String =
            ver match
              case Http1_0 => "--http1.0"
              case Http1_1 => "--http1.1"
              case Http2   => "--http2"
              case Http3   => "--http3"

          request.version match
            case None      => Seq.empty
            case Some(ver) => Seq(from(ver))
        }

        def certificate: Seq[String] = {
          request.certificate match
            case None                             => Seq.empty
            case Some(PemClientCertificate(file)) => Seq("--cert", file.pathAsString)
        }

        def requestMethod: Seq[String] = {
          request.method match {
            case HttpMethod.Get    => Seq("-X", "GET")
            case HttpMethod.Post   => Seq("-X", "POST")
            case HttpMethod.Put    => Seq("-X", "HEAD")
            case HttpMethod.Head   => Seq("-X", "PUT")
            case HttpMethod.Delete => Seq("-X", "DELETE")
          }
        }

        def cookies: Seq[String] = {
          //Seq("--cookie-jar", "/tmp/cookies", "--cookie", "/tmp/cookies")
          request.cookies.toSeq.flatMap(cookie => Seq("--cookie", s"${cookie.name}=${cookie.value}"))
        }

        def headerFields: Seq[String] = {

          def mockHeaders: Seq[String] = {
            session.environment match {
              case Production => Seq.empty
              case _ : Test   => Seq(
                s"X-Zealot-RequestNumber:$count",
                s"X-Zealot-TargetUrl:$targetUrl"
              )

            }
          }

//          val fromBody = (request.method, request.body) match {
//            case (HttpMethod.Post, JsonBody(_)) => Seq("Content-Type:application/json")
//            case _                              => Seq.empty
//          }

          Seq("-A", request.ua) ++ (mockHeaders ++ (for {
            (name, values) <- request.headers
            value          <- values
          } yield (s"$name:$value")).toSeq).flatMap(value => Seq("-H", value))
        }

        def formFields: Seq[String] = {

          def encodeData(pairs: Seq[(String, String)]): Seq[String] = {
            pairs.flatMap {
              case (name, value) => Seq("-d", s"$name=$value")
            }
          }

          def encodeUrl(pairs: Seq[(String, String)]): Seq[String] = {
            pairs.flatMap {
              case (name, value) => Seq("--data-urlencode", s"$name=$value")
            }
          }

          def encodeMultipart(pairs: Seq[(String, String)]): Seq[String] = {
            pairs.flatMap {
              case (name, value) => Seq("-F", s"$name=$value")
            }
          }

          def encodeDataRaw   (pairs: Seq[(String, String)]): Seq[String] = ???
          def encodeDataBinary(pairs: Seq[(String, String)]): Seq[String] = ???

          request.method match {
            case HttpMethod.Post =>

              val pairs = for {
                (name, values) <- request.fields.toSeq.sortBy(_._1)
                value          <- values
              } yield (name, value)

              request.formEncoding match {
                case Data          => encodeData       (pairs)
                case DataRaw       => encodeDataRaw    (pairs)
                case DataBinary    => encodeDataBinary (pairs)
                case DataUrlEncode => encodeUrl        (pairs)
                case Multipart     => encodeMultipart  (pairs)
              }

            case _ => Seq.empty
          }
        }

        def dumpHeaders : Seq[String] = Seq("-D", headersFile.toString)
        def dumpOutput  : Seq[String] = Seq("-o", bodyFile.toString)

        def dumpBody: Seq[String] = {
          (request.method, request.body) match
            case (HttpMethod.Post, JsonBody(ast))       => Seq("--json"     , ast.toString())
            case (HttpMethod.Post, StringBody(text, _)) => Seq("--data-raw" , text)
            case _  => Seq.empty

        }

        def proxy = Seq.empty[String] //Seq("--proxy","http://localhost:8080", "https://localhost:8080")

        ZIO.attempt(
           curl ++ version ++ requestMethod ++ proxy ++ certificate ++ cookies ++ headerFields ++ formFields ++ dumpHeaders ++ dumpBody ++ dumpOutput ++ Seq(url)
        )
      }

      def run(cmd: Seq[String]) = {
        val sb = new StringBuffer

        val logger = new ProcessLogger {
          def append(tag: String, value: String) = if (value.nonEmpty) sb.append(tag).append(value).append("\n")

          override def out(s: => String): Unit = append("[OUT] ", s)
          override def err(s: => String): Unit = append("[ERR] ", s)
          override def buffer[T](f: => T) = f
        }

        Try(Process(cmd, ctx.root.toJava) !< logger) match
          case Success(0)     => ZIO.succeed(sb.toString())
          case Success(code)  => ZIO.fail(new Exception(s"Error running command '$cmd' at '${ctx.root}' ($code): ${sb.toString}"))
          case Failure(error) => ZIO.fail(new Exception(s"Error running command '$cmd' at '${ctx.root}'", error))
      }

      def parse(url: String, headersFile: File, bodyFile: File, output: String): Task[DefaultHttpResponse] = {
        //TODO: make this lazy
        val lines = headersFile.lines.map(_.trim).filterNot(_.isEmpty)

        val start = lines.zipWithIndex.foldLeft(0) { (index, pair) =>
          if (pair._1.startsWith("HTTP")) pair._2 else index
        }

        def readStatusCode: Task[Int] = {
          lines.slice(start, start + 1).headOption match
            case None       => ZIO.fail(new Exception("Missing response status"))
            case Some(head) => ZIO.attempt(head.split(" ")(1).toInt).mapError(e => new Exception("Error parsing response status", e))
        }

        def readHeaders: Task[Map[String, Set[String]]] = {
          def toHeader(line: String): (String, String) = {
            val idx = line.indexOf(":")
            (line.substring(0, idx).trim.toLowerCase, line.substring(idx + 1).trim)
          }
          ZIO.attempt(lines.drop(start + 1).map(_.trim).filterNot(_.isEmpty).map(toHeader).groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap)
        }

        def readCharset(headers: Map[String, Set[String]]): Task[Option[Charset]] = {
          headers.get("content-type").flatMap(_.headOption).map(_.split(";")) match
            case Some(Array(_, charset)) if charset.startsWith("charset=") =>
              ZIO.attempt(Charset.forName(charset.trim.substring("charset=".length))).map(Some(_))
            case _ =>
              ZIO.succeed(None)
        }

        def parseCookies(headers: Map[String, Set[String]]): Task[Seq[ResponseCookie]] = {

          def toCookie(value: String): Task[ResponseCookie] = ZIO.fromTry(DefaultCookie.from(url, value))

          val values = headers filter {
            case (name, _) => name.equalsIgnoreCase("set-cookie")
          } flatMap {
            case (_, values) => values
          }

          ZIO.foreach(values.toSeq)(toCookie)
        }

        for {
          code    <- readStatusCode
          headers <- readHeaders
          charset <- readCharset(headers)
          cookies <- parseCookies(headers)
        } yield DefaultHttpResponse(url, code, charset, headers, cookies, bodyFile)
      }

      def ensureFile(name: String): Task[File] = {
        val target = ctx.root / name
        ZIO.attempt(target.touch())
      }

      def dumpRequest(cmd: Seq[String], file: File): Task[Unit] = {
        val prefix = trace.toString match {
          case traceRegex(location, file, line) => s"""{"location": "$location", "file":"$file", "line": $line}\n"""
          case _                                => ""
        }

        for {
          _ <- ZIO.attempt(file.writeText(prefix))
          _ <- ZIO.attempt(file.appendText(cmd.mkString(" \\\n")))
        } yield ()
      }

      def renameResponseFile(response: DefaultHttpResponse, file: File): Task[HttpResponse] = {

        def rename(ctype: String): Task[HttpResponse] = {

          def extensionGiven(ctype: String): String = {
            ctype match {
              case "text/plain"               => "txt"
              case "text/csv"                 => "csv"
              case "application/json"         => "json"
              case "application/pdf"          => "pdf"
              case "application/octet-stream" => "bin"
              case "image/png"                => "png"
              case "image/gif"                => "gif"
              case "image/jpeg"               => "jpeg"
              case "image/jpg"                => "jpg"
              case _                          => "html"
            }
          }

          def renameTo(extension: String): File = {
            val name = file.nameWithoutExtension
            file.renameTo(name + "." + extension)
          }

          for {
            ext     <- ZIO.attempt(extensionGiven(ctype))
            replace <- ZIO.attempt(renameTo(ext))
          } yield response.copy(body = replace)
        }

        response.headers.get("content-type").flatMap(_.headOption).map(_.split(";")) match {
          case Some(Array(value, _)) => rename(value)
          case Some(Array(value))    => rename(value)
          case _                     => ZIO.succeed(response)
        }
      }

      def printRequest(count: Int): ZLT[Unit] = {
        def encodedUrlToParamMap(encodedURL: String): Map[String, String] = {
          encodedURL.split('?').toList match {
            case _ :: params :: Nil => params.split("&").collect { case s"$key=$value" => key -> URLDecoder.decode(value) }.toMap
            case _                  => Map.empty[String, String]
          }
        }

        val params = request.parameters ++ encodedUrlToParamMap(targetUrl).view.mapValues(Set(_))
        println(s"\n>> [$count - ${request.name.getOrElse("_")}] ${request.method.toString.toUpperCase} ${targetUrl}")
        request.headers       .toSeq.sortBy(_._1)  .foreach { h => println(s" header > ${h._1}=${h._2.mkString(", ")}") }
        request.cookies       .toSeq.sortBy(_.name).foreach { c => println(s" cookie > ${c.name}=${c.value}")           }
        params                .toSeq.sortBy(_._1)  .foreach { p => println(s" param  > ${p._1}=${p._2.mkString(", ")}") }
        if(request.method== HttpMethod.Post) {
          (for {
            (name, values) <- request.fields.toSeq.sortBy(_._1)
            value          <- values
          } yield (name, value)).foreach { case (name, value) =>
            println(s" data   > $name=$value")
          }
        }
        ZIO.unit
      }

      def printResponse(now: ZonedDateTime, res: HttpResponse): ZLT[Unit] = {
        if (res.code == 302) {
          val location = res.header("location").getOrElse("???")
          println(s"<< ${res.code} (location: $location)")
        } else {
          val ctype = res.header("content-type").getOrElse("???")
          println(s"<< ${res.code} (content-type: $ctype)")
        }
        res.cookies.sortBy(_.name).foreach { c =>
          val remove = c.expires.exists(_.isBefore(now))
          println(s" cookie < ${if(remove) "-" else "+"} ${c.name}=${c.value} (${c.domain}|${c.path})")
        }
        ZIO.unit
      }

      val tag = request.name.map(name => "-"+name).getOrElse("")
      val url = getUrl
      for {
        count        <- counter.updateAndGet(_ + 1)
        reqFile      <- ensureFile(name = s"resp-${count}${tag}.req")     .mapError(onError("Error creating request file"     ))
        resFile      <- ensureFile(name = s"resp-${count}${tag}.res")     .mapError(onError("Error creating response file"    ))
        headersFile  <- ensureFile(name = s"resp-${count}${tag}.headers") .mapError(onError("Error creating headers file"     ))
        _            <- printRequest(count)
        cmd          <- build(count, url, headersFile, resFile)           .mapError(onError("Error building curl command line"))
        _            <- dumpRequest(cmd, reqFile)                         .mapError(onError("Error dumping request"           ))
        output       <- run(cmd)                                          .mapError(onError("Error executing curl"            ))
        response     <- parse(url, headersFile, resFile, output)          .mapError(onError("Error parsing curl response"     ))
        now          <- Clock.currentDateTime
        _            <- printResponse(now.toZonedDateTime, response)
        renamed      <- renameResponseFile(response, resFile)             .mapError(onError("Error renaming response file"    ))
      } yield renamed
    }
  }
}