package zealot.http

object curl {

  import better.files.*
  import zealot.commons.*
  import zealot.commons.Outcome.*
  import zealot.http.FormEncoding.*
  import zio.*
  import zio.process.Command

  import java.net.URLDecoder
  import java.nio.charset.Charset
  import java.time.ZonedDateTime
  import scala.sys.process.*
  import scala.util.{Failure, Success, Try}

  object CurlHttpEngine {
    def layer = ZLayer.succeed { CurlHttpEngine() }
  }

  object ResponseParser {
    def parse(url: String, headersFile: File, bodyFile: File, output: String): Task[DefaultHttpResponse] = {

      def readLines: Task[Seq[String]] = {
        ZIO.attempt {
          headersFile.lines(using HttpUtils.iso).toSeq
        }
      }

      def readStatusCode(lines: Seq[String]): Task[Int] = {
        lines.headOption match
          case Some(head) if head.startsWith("HTTP") => ZIO.attempt(head.split(" ")(1).toInt).mapError(e => Exception("Error parsing response status from header", e))
          case                                     _ => ZIO.fail(Exception("Missing response status"))
      }

      def readHeaders(lines: Seq[String]): Task[Map[String, Set[String]]] = {
        def toHeader(line: String): (String, String) = {
          //println(s"Parsing Header '${line}'")
          val idx = line.indexOf(":")
          (line.substring(0, idx).trim.toLowerCase, line.substring(idx + 1).trim)
        }
        ZIO.attempt(lines.drop(1).map(_.trim).filterNot(_.isEmpty).map(toHeader).groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap)
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

      def dedup(lines: Seq[String]): Task[Seq[String]] = {
        //It might be the case the we have multiple responses in the same file, specially when calling curl with '--proxy'
        //Drop the first response and keep the second
        ZIO.attempt {
          val (s1, s2) = lines.map(_.trim).span(_.nonEmpty)
          (s1.filterNot(_.isEmpty), s2.filterNot(_.isEmpty)) match
            case (Nil, Nil) => Seq.empty
            case (seq, Nil) => seq
            case (_, seq)   => seq
        }
      }

      def normalize(lines: Seq[String]): Task[Seq[String]] = {
        lines
          .zipWithIndex
          .filter( (line, idx) => line.startsWith("HTTP") )
          .map(_._2)
          .lastOption match {
            case None      => ZIO.fail(Exception("Can't find line with HTTP response and status code"))
            case Some(idx) => ZIO.succeed(lines.drop(idx))
          }
      }

      for {
        lines   <- readLines
        deduped <- normalize(lines)
        code    <- readStatusCode(deduped)
        headers <- readHeaders(deduped)
        charset <- readCharset(headers)
        cookies <- parseCookies(headers)
      } yield DefaultHttpResponse(url, code, charset, headers, cookies, bodyFile)
    }
  }

  /* see https://curl.se/libcurl/c/libcurl-errors.html */
  val CurlErrors = Map(
    1   -> "UNSUPPORTED_PROTOCOL",
    2   -> "FAILED_INIT",
    3   -> "URL_MALFORMAT",
    4   -> "NOT_BUILT_IN",
    5   -> "COULDNT_RESOLVE_PROXY",
    6   -> "COULDNT_RESOLVE_HOST",
    7   -> "COULDNT_CONNECT",
    8   -> "WEIRD_SERVER_REPLY",
    9   -> "REMOTE_ACCESS_DENIED",
    10  -> "FTP_ACCEPT_FAILED",
    16  -> "HTTP2",
    18  -> "PARTIAL_FILE",
    21  -> "QUOTE_ERROR",
    22  -> "HTTP_RETURNED_ERROR",
    23  -> "WRITE_ERROR",
    25  -> "UPLOAD_FAILED",
    27  -> "OUT_OF_MEMORY",
    30  -> "OPERATION_TIMEDOUT",
    33  -> "RANGE_ERROR",
    35  -> "SSL_CONNECT_ERROR",
    36  -> "BAD_DOWNLOAD_RESUME",
    37  -> "FILE_COULDNT_READ_FILE",
    42  -> "ABORTED_BY_CALLBACK",
    43  -> "BAD_FUNCTION_ARGUMENT",
    45  -> "INTERFACE_FAILED",
    47  -> "TOO_MANY_REDIRECTS",
    48  -> "UNKNOWN_OPTION",
    49  -> "SETOPT_OPTION_SYNTAX",
    52  -> "GOT_NOTHING",
    53  -> "SSL_ENGINE_NOTFOUND",
    54  -> "SSL_ENGINE_SETFAILED",
    55  -> "SEND_ERROR",
    56  -> "RECV_ERROR",
    58  -> "SSL_CERTPROBLEM",
    59  -> "SSL_CIPHER",
    60  -> "PEER_FAILED_VERIFICATION",
    61  -> "BAD_CONTENT_ENCODING",
    63  -> "FILESIZE_EXCEEDED",
    64  -> "USE_SSL_FAILED",
    65  -> "SEND_FAIL_REWIND",
    66  -> "SSL_ENGINE_INITFAILED",
    67  -> "LOGIN_DENIED",
    70  -> "REMOTE_DISK_FULL",
    73  -> "REMOTE_FILE_EXISTS",
    77  -> "SSL_CACERT_BADFILE",
    78  -> "REMOTE_FILE_NOT_FOUND",
    79  -> "SSH",
    80  -> "SSL_SHUTDOWN_FAILED",
    81  -> "AGAIN",
    82  -> "SSL_CRL_BADFILE",
    83  -> "SSL_ISSUER_ERROR",
    88  -> "CHUNK_FAILED",
    89  -> "NO_CONNECTION_AVAILABLE",
    90  -> "SSL_PINNEDPUBKEYNOTMATCH",
    91  -> "SSL_INVALIDCERTSTATUS",
    92  -> "HTTP2_STREAM",
    93  -> "RECURSIVE_API_CALL",
    94  -> "AUTH_ERROR",
    95  -> "HTTP3",
    96  -> "QUIC_CONNECT_ERROR",
    97  -> "PROXY",
    98  -> "SSL_CLIENTCERT",
    99  -> "UNRECOVERABLE_POLL",
    100 -> "TOO_LARGE",
    101 -> "ECH_REQUIRED",
  )

  case class CurlError(code: Int, message: Option[String], command: Seq[String], stderr: String) extends Exception(s"Curl Error ($code/${message.getOrElse("???")}): $stderr")

  /*
   * See https://ec.haxx.se/index.html
   */
  case class CurlHttpEngine() extends HttpEngine {

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
            case None                                           => Seq.empty
            case Some(PemClientCertificate(file))               => Seq("--cert-type", "PEM", "--cert", file.pathAsString)
            case Some(Pkcs12ClientCertificate(file, None))      => Seq("--cert-type", "P12", "--cert", file.pathAsString)
            case Some(Pkcs12ClientCertificate(file, Some(pwd))) => Seq("--cert-type", "P12", "--cert", file.pathAsString + ":" + pwd)
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

        def proxy: Seq[String] = {
          def proxyGiven(p: HttpProxy): Seq[String] = {
            val hostAndPort = s"${p.host}:${p.port}"
            Seq("--proxy", p.auth.map(a => s"${a.username}:${a.password}@$hostAndPort").getOrElse(hostAndPort))
          }

          session.proxy.map(proxyGiven).getOrElse(Seq.empty)
        }

        ZIO.attempt(
           curl ++ version ++ requestMethod ++ proxy ++ certificate ++ cookies ++ headerFields ++ formFields ++ dumpHeaders ++ dumpBody ++ dumpOutput ++ Seq(url)
        )
      }

      def run(cmd: Seq[String]): Task[String] = {
        val head = cmd.head
        val tail = cmd.tail

        for
          proc <- Command(head, tail: _*).workingDirectory(ctx.root.toJava).run
          code <- proc.exitCode
          out  <- proc.stdout.string
          err  <- proc.stderr.string
          _ <- code.code match
                 case 0     => ZIO.unit
                 case other => ZIO.fail(CurlError(other, CurlErrors.get(code.code), cmd, err))
        yield out
      }

      def run0(cmd: Seq[String]): Task[String] = {
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

        val data = if(request.method != HttpMethod.Post) Seq.empty else {
          for {
            (name, values) <- request.fields.toSeq.sortBy(_._1)
            value          <- values
          } yield (name, value)
        }

        for
          _ <-                                                               ctx.logger.zlt(s"\n>> [$count - ${request.name.getOrElse("_")}] ${request.method.toString.toUpperCase} ${targetUrl}")
          _ <- ZIO.foreach(request.headers.toSeq.sortBy(_._1))   { h      => ctx.logger.zlt(s" header > ${h._1}=${h._2.mkString(", ")}") }
          _ <- ZIO.foreach(request.cookies.toSeq.sortBy(_.name)) { c      => ctx.logger.zlt(s" cookie > ${c.name}=${c.value}")           }
          _ <- ZIO.foreach(params         .toSeq.sortBy(_._1))   { p      => ctx.logger.zlt(s" param  > ${p._1}=${p._2.mkString(", ")}") }
          _ <- ZIO.foreach(data)                                 { (n, v) => ctx.logger.zlt(s" data   > $n=$v")                   }
        yield ()
      }

      def printResponse(now: ZonedDateTime, res: HttpResponse): ZLT[Unit] = {

        val location = res.header("location")    .getOrElse("???")
        val ctype    = res.header("content-type").getOrElse("???")

        def printCookie(c: ResponseCookie) = {
          val remove = c.expires.exists(_.isBefore(now))
          ctx.logger.zlt(s" cookie < ${if(remove) "-" else "+"} ${c.name}=${c.value} (${c.domain}|${c.path})")
        }

        for
          _ <- if (res.code == 302) ctx.logger.zlt(s"<< ${res.code} (location: $location)") else ctx.logger.zlt(s"<< ${res.code} (content-type: $ctype)")
          _ <- ZIO.foreach(res.cookies.sortBy(_.name)) { printCookie }
        yield ()
      }

      val tag = request.name.map(name => "-"+name).getOrElse("")
      val url = getUrl
      for {
        count        <- session.count
        reqFile      <- ensureFile(name = s"resp-${count}${tag}.req")           .mapError(onError("Error creating request file"     ))
        resFile      <- ensureFile(name = s"resp-${count}${tag}.res")           .mapError(onError("Error creating response file"    ))
        headersFile  <- ensureFile(name = s"resp-${count}${tag}.headers")       .mapError(onError("Error creating headers file"     ))
        _            <- printRequest(count)
        cmd          <- build(count, url, headersFile, resFile)                 .mapError(onError("Error building curl command line"))
        _            <- dumpRequest(cmd, reqFile)                               .mapError(onError("Error dumping request"           ))
        output       <- run(cmd)                                                .mapError(onError("Error executing curl"            ))
        response     <- ResponseParser.parse(url, headersFile, resFile, output) .mapError(onError("Error parsing curl response"     ))
        now          <- Clock.currentDateTime
        _            <- printResponse(now.toZonedDateTime, response)
        renamed      <- renameResponseFile(response, resFile)                   .mapError(onError("Error renaming response file"    ))
      } yield renamed
    }
  }
}