package zealot.http

import better.files.File
import org.jsoup.Jsoup
import org.jsoup.nodes.*
import zealot.commons.*
import zealot.commons.Outcome.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.io.{FileInputStream, InputStream}
import java.net.{URI, URL, URLEncoder}
import java.nio.charset.Charset
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import scala.collection.immutable
import scala.jdk.CollectionConverters.*
import scala.util.Try

trait HttpInterceptor {
  def handle  (request: HttpRequest, response: HttpResponse): ZLT[HttpResponse] = ZIO.succeed(response)
  def onFollow(request: HttpRequest, response: HttpResponse): ZLT[HttpResponse] = ZIO.succeed(response)
}

enum HttpMethod:
  case Head, Get, Post, Put, Delete

enum FormEncoding:
  case Data, DataRaw, DataBinary, DataUrlEncode, Multipart

trait RequestCookie {
  def name    : String
  def value   : String
}

trait ResponseCookie extends RequestCookie {
  def url       : String // the request url the received this cookie as response
  def path      : Option[String]
  def domain    : Option[String]
  def secure    : Option[Boolean]
  def httpOnly  : Option[Boolean]
  def expires   : Option[ZonedDateTime]
  def maxAge    : Option[Long]
  def toRequest : RequestCookie = this
}

trait HtmlElement {
  def tag                        : ZLT[String]
  def is(tag: String)            : ZLT[Boolean]
  def byId(id: String)           : ZLT[HtmlElement]
  def byIdOpt(id: String)        : ZLT[Option[HtmlElement]]
  def byName(name: String)       : ZLT[HtmlElement]
  def select(expression: String) : ZLT[Seq[HtmlElement]]
  def attr(name: String)         : ZLT[String]
  def attrOpt(name: String)      : ZLT[Option[String]]
  def text                       : ZLT[String]
  def html                       : ZLT[String]
  def fnOpt[T](id: String)(fn: HtmlElement => ZLT[T]): ZLT[Option[T]]
}

trait HtmlForm extends HtmlElement {
  def method: String
  def action: String
  def values: Map[String, String]
  def mergeValues(values: Map[String, String]): ZLT[HtmlForm]
}

trait HttpResponse {
  def code                                      : Int
  def charset                                   : Option[Charset]
  def headers                                   : Map[String, Set[String]]
  def cookies                                   : Seq[ResponseCookie]
  def body                                      : File
  def bodyAs[T](using decoder: JsonDecoder[T])  : ZLT[T]
  def bodyAsString                              : ZLT[String]
  def redirect                                  : ZLT[String]
  def document(using session: HttpSession)      : ZLT[HtmlElement]
  def header(name: String)                      : Option[String]
  def requestedUrl                              : String
}

@FunctionalInterface
trait Expectation {
  def assert(request: ExecutableHttpRequest, response: HttpResponse): ZIO[Any, BotError, Unit]
}

object Expectation {
  def is200             : Expectation = status(200)
  def isRedirect        : Expectation = status(302)
  def status(code: Int) : Expectation = (request: ExecutableHttpRequest, response: HttpResponse) => if(response.code == code) ZIO.unit else ZIO.fail(BotError(HttpError, s"Código de resposta inesperado para url '${request.url}'."))
}

sealed trait HttpBody

case object NoBody                                                   extends HttpBody
case class JsonBody(ast: Json)                                       extends HttpBody
case class StringBody(text: String, charset: Option[Charset] = None) extends HttpBody

sealed trait HttpVersion
case object Http1_0 extends HttpVersion
case object Http1_1 extends HttpVersion
case object Http2   extends HttpVersion
case object Http3   extends HttpVersion

sealed trait ClientCertificate
case class PemClientCertificate(file: File) extends ClientCertificate

trait HttpRequest {
  def url             (replace: String)                    : HttpRequest
  def header          (name: String, value: String)        : HttpRequest
  def param           (name: String, value: String)        : HttpRequest
  def cookie          (name: String, value: String)        : HttpRequest
  def cookies         (values: Map[String, String])        : HttpRequest
  def field           (name: String, value: String)        : HttpRequest
  def formEncoding    (encoding: FormEncoding)             : HttpRequest
  def followRedirects (follow: Boolean)                    : HttpRequest
  def certificate     (cert: ClientCertificate)            : HttpRequest
  def version         (ver: HttpVersion)                   : HttpRequest
  def body            (body: HttpBody)                     : HttpRequest
  def body[T]         (body: T)(using enc: JsonEncoder[T]) : ZLT[HttpRequest]
  def named           (name: String)                       : ExecutableHttpRequest
}

trait HttpContext {
  def root: File
}

trait ExecutableHttpRequest {
  def url             : String
  def ua              : String
  def name            : Option[String]
  def method          : HttpMethod
  def parameters      : Map[String, Set[String]]
  def headers         : Map[String, Set[String]]
  def fields          : Map[String, Set[String]]
  def cookies         : Set[RequestCookie]
  def body            : HttpBody
  def formEncoding    : FormEncoding
  def followRedirects : Boolean
  def certificate     : Option[ClientCertificate]
  def version         : Option[HttpVersion]
  def execute (expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
  def get     (expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
  def post    (expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
}

trait HttpEngine {
  def execute(request: ExecutableHttpRequest)(using ctx: HttpContext, session: HttpSession, trace: Trace): ZLT[HttpResponse]
}

sealed trait HttpEnvironment
case object Production                                     extends HttpEnvironment
case class Test(host: String, port: Int, scenario: String) extends HttpEnvironment

case class ProxyAuth(username: String, password: String)

case class HttpProxy(
  host : String,
  port : Int,
  auth : Option[ProxyAuth] = None
)

trait HttpSession {
  def environment : HttpEnvironment
  def charset     : Charset
  def baseUrl     : String
  def ua          : String
  def certificate : Option[ClientCertificate]
  def proxy       : Option[HttpProxy]
  def update(request: ExecutableHttpRequest, response: HttpResponse): ZLT[Unit]
  def requestGiven(url: String)    : ZLT[HttpRequest]
  def requestGiven(form: HtmlForm) : ZLT[HttpRequest]
  def rebase(baseUrl: String)      : ZLT[HttpSession]
  def cookies                      : ZLT[Cookies]
  def cookiesGiven(url: String)    : ZLT[Seq[ResponseCookie]]
  def count                        : ZLT[Int]
}

trait Http {
  def session(
    charset     : Charset,
    baseUrl     : String,
    ua          : String,
    cookies     : Cookies                   = Cookies.from(Seq.empty),
    proxy       : Option[HttpProxy]         = None,
    certificate : Option[ClientCertificate] = None
  )(using environment: HttpEnvironment) : ZLT[HttpSession]

  def url(url: String)            (using session: HttpSession): ZLT[HttpRequest]
  def requestGiven(form: HtmlForm)(using session: HttpSession): ZLT[HttpRequest]
}

trait Script

/* IMPL */
case class ExternalScript(src: String) extends Script
case class InlineScript(body: String)  extends Script

case class DefaultRequestCookie(name: String, value: String) extends RequestCookie

case class DefaultResponseCookie(
  url      : String,
  name     : String,
  value    : String,
  domain   : Option[String]        = None,
  path     : Option[String]        = None,
  secure   : Option[Boolean]       = None,
  httpOnly : Option[Boolean]       = None,
  maxAge   : Option[Long]          = None,
  expires  : Option[ZonedDateTime] = None
) extends ResponseCookie

object DefaultCookie {
  /*
    https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie
  */
  private val formatter = DateTimeFormatter.RFC_1123_DATE_TIME

  /*
    Wed, 21 Oct 2015 07:28:00 GMT
    Thu, 01-Jan-1970 00:00:10 GMT
  */
  private val re1 = s"[a-zA-Z]{3}, (\\d{2}) ([a-zA-Z]{3}) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT".r
  private val re2 = s"[a-zA-Z]{3}, (\\d{2})\\-([a-zA-Z]{3})\\-(\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT".r

  def from(url: String, str0: String, dropDoubleQuotes: Boolean = true): Try[ResponseCookie] = Try {

    def parseDate(value: String): ZonedDateTime = {

      def toMonth(name: String): Int = {
        name match
          case "Jan" => 1
          case "Feb" => 2
          case "Mar" => 3
          case "Apr" => 4
          case "May" => 5
          case "Jun" => 6
          case "Jul" => 7
          case "Aug" => 8
          case "Sep" => 9
          case "Oct" => 10
          case "Nov" => 11
          case "Dec" => 12
      }
      value match
        case re1(day, month, year, hour, minute, second) => ZonedDateTime.of(year.toInt, toMonth(month), day.toInt, hour.toInt, minute.toInt, second.toInt, 0, ZoneId.of("Z"))
        case re2(day, month, year, hour, minute, second) => ZonedDateTime.of(year.toInt, toMonth(month), day.toInt, hour.toInt, minute.toInt, second.toInt, 0, ZoneId.of("Z"))
        case _                                           => throw new Exception(s"Unparsable date '$value'")
    }

    def pairGiven(str: String) = {
      str.split("=") match {
        case Array(name)         => (name, "")
        case Array(name, value)  => (name, value)
        case Array(name, v1, v2) => (name, v1 + "=" + v2)
        case _                   => throw new Exception(s"Can't extract name/value pair from '$str'")
      }
    }

    val str1 = if(dropDoubleQuotes && str0.startsWith("\"")) str0.drop(1)      else str0
    val str2 = if(dropDoubleQuotes && str1.endsWith("\""))   str1.dropRight(1) else str1
    str2
      .split(";")
      .map(_.trim)
      .zipWithIndex
      .foldLeft(DefaultResponseCookie(url, "", "")) { (cookie, tuple) =>
        val slice = tuple._1
        val index = tuple._2
        if(index == 0) {
          val (name, value) = pairGiven(tuple._1)
          cookie.copy(name = name, value = value)
        } else {
          if(slice.contains("=")) {
            val (name, value) = pairGiven(slice)
            name.toLowerCase match {
              case "expires"  => cookie.copy(expires = Some(parseDate(value)))
              case "domain"   => cookie.copy(domain  = Some(value))
              case "path"     => cookie.copy(path    = Some(value))
              case "max-age"  => cookie.copy(maxAge  = Some(value.toLong))
              case "samesite" => cookie
              case _          => cookie
            }
          } else {
            slice.toLowerCase match {
              case "httponly"    => cookie.copy(httpOnly = Some(true))
              case "secure"      => cookie.copy(secure   = Some(true))
              case "partitioned" => cookie
              case _             => cookie
            }
          }
        }
      }
  }
}

case class DefaultHttpSession(
  counter     : Ref[Int],
  history     : Ref[Seq[(ZonedDateTime, String)]],
  ref         : Ref[Cookies],
  environment : HttpEnvironment,
  charset     : Charset,
  baseUrl     : String,
  ua          : String,
  proxy       : Option[HttpProxy] = None,
  certificate : Option[ClientCertificate] = None
) extends HttpSession {

  private def domainGiven(url: String): ZLT[String] = {
    if (url.startsWith("http://") || url.startsWith("https://")) {
      (for {
        abs <- ZIO.attempt(new URL(url).toURI)
      } yield abs.getHost).mapError(e => BotError(HttpError, s"Error extraindo domínio de '$url'", Some(e)))
    } else {
      domainGiven(baseUrl + url)
    }
  }

  private def cookiesByDomain(domain: String): ZLT[Set[ResponseCookie]] = {
    for {
      cookies <- ref.get
    } yield cookies.cache.view.filterKeys(domain.endsWith).values.toSet.flatten
  }

  override def count: ZLT[Int] = counter.getAndUpdate(_ + 1)
  override def update(request: ExecutableHttpRequest, response: HttpResponse): ZLT[Unit] = {

    //      response.headers.foreach {
    //        case (name, values) =>
    //          values.foreach(v => println(s"[$name] = [$v]"))
    //      }

    def update: Task[Unit] = {

      def update(now: ZonedDateTime, domain: String)(cookie: ResponseCookie): Task[Unit] = {
        val remove = cookie.expires.exists(_.isBefore(now))
        ref.update { _.updateDomain(domain, cookie, remove) }
      }

      given HttpSession = this

      for {
        now     <- Clock.currentDateTime.map(_.toZonedDateTime)
        _       <- history.update(_ :+ (now, response.requestedUrl))
        domain  <- domainGiven(request.url).mapError(be => be.cause.map(new Exception(_)).getOrElse(new Exception(s"Error extraindo domínio da url '${request.url}'")))
        _       <- ZIO.foreach(response.cookies) { update(now, domain) }
      } yield ZIO.unit
    }

    update.mapError(e => BotError(UnexpectedError, "Erro atualizando estado interno do navegador", Some(e)))
  }

  override def requestGiven(url: String) = {
    for {
      domain  <- domainGiven(url)
      cookies <- cookiesByDomain(domain)
    } yield DefaultHttpRequest(
      url         = url,
      ua          = ua,
      cookies     = cookies.map(_.toRequest),
      certificate = certificate
    )
  }

  override def requestGiven(form: HtmlForm) = {
    def build = {

      val method = form.method.toLowerCase() match {
        case "get"  => Some(HttpMethod.Get)
        case "post" => Some(HttpMethod.Post)
        case _      => None
      }

      for {
        domain  <- domainGiven(form.action)
        cookies <- cookiesByDomain(domain)
      } yield DefaultHttpRequest(
        url         = form.action,
        ua          = ua,
        method      = method.getOrElse(HttpMethod.Get),
        fields      = form.values.view.mapValues(Set(_)).toMap,
        cookies     = cookies.map(_.toRequest),
        certificate = certificate
      )
    }

    build.mapError(_.as(HtmlDocumentError, "Erro ao criar requisição baseada no formulário"))
  }

  override def rebase(url: String): ZLT[HttpSession] = {
    for {
      cookies  <- ref.get
      original <- domainGiven(baseUrl)
      domain   <- domainGiven(url)
      newRef  <- cookies.cache.get(original) match
        case None      => ZIO.succeed(ref)
        case Some(set) =>
          val a = cookies.cache - baseUrl
          val b = a + (domain -> set)
          Ref.make(Cookies(b))

    } yield copy(baseUrl = url, ref = newRef)
  }

  override def cookies: ZLT[Cookies] = {
    ref.get
  }

  override def cookiesGiven(url: String): ZLT[Seq[ResponseCookie]] = {
    for {
      dom    <- domainGiven(url)
      result <- cookiesByDomain(dom)
    } yield result.toSeq
  }
}

case class DefaultHttpResponse(
  requestedUrl : String,
  code         : Int,
  charset      : Option[Charset],
  headers      : Map[String, Set[String]],
  cookies      : Seq[ResponseCookie],
  body         : File) extends HttpResponse {

  override def document(using session: HttpSession): ZLT[HtmlElement] = {

    def open: Task[InputStream] = ZIO.attempt(new FileInputStream(body.toJava))

    def close(is: InputStream): UIO[Unit] = {
      ZIO.attempt(is.close()).catchAll { error =>
        for {
          _  <- ZIO.logErrorCause("Error closing InputStream", Cause.fail(error))
        } yield ()
      }
    }

    def parse(is: InputStream): Task[Document] = ZIO.attempt(Jsoup.parse(is, session.charset.name(), session.baseUrl))

    for {
      parsed <- ZIO.acquireReleaseWith(open)(close)(parse).mapError(e => BotError(HtmlDocumentError, "Erro ao ler documento HTML", Some(e)))
    } yield DefaultHtmlElement(session.charset, parsed)
  }

  override def header(name: String): Option[String] = {
    headers.find {
      case (n, _) => n.equalsIgnoreCase(name)
    } flatMap {
      (v, values) => values.headOption
    }
  }

  override def redirect: ZLT[String] = {
    ZIO.fromOption(header("location")).mapError(_ => BotError(HttpError, "Redirecionamento Inválido"))
  }

  override def bodyAs[T](using decoder: JsonDecoder[T]): ZLT[T] = {
    for {
      text  <- bodyAsString
      value <- text.fromJson[T] match {
                 case Left(msg)    => ZIO.fail(BotError(HtmlDocumentError, s"Erro ao ler o corpo da resposta no modo json: $msg"))
                 case Right(value) => ZIO.succeed(value)
               }
    } yield value
  }

  override def bodyAsString: ZLT[String] = {
    ZIO.attempt(body.contentAsString).mapError(BotError.of(HtmlDocumentError, "Erro ao ler o corpo da resposta no modo texto"))
  }
}

case class DefaultHtmlElement(charset: Charset, inner: Element) extends HtmlElement {

  override def tag: ZLT[String] = {
    ZIO
      .attempt(inner.tagName())
      .mapError(e => BotError(HtmlDocumentError, s"Erro ao extrair o nome do tag HTML", Some(e)))
  }

  override def is(name: String): ZLT[Boolean] = {
    for {
      t <- tag
    } yield t == name
  }

  override def byId(id: String): ZLT[HtmlElement] = {
    val element = inner.getElementById(id)
    if (element == null) ZIO.fail(BotError(HtmlDocumentError, s"Elemento '$id' não encontrado na página"))
    else                 ZIO.succeed(DefaultHtmlElement(charset, element))
  }

  override def byIdOpt(id: String): ZLT[Option[HtmlElement]] = {
    val element = inner.getElementById(id)
    if(element == null) ZIO.succeed(None)
    else                ZIO.succeed(Some(DefaultHtmlElement(charset, element)))
  }

  override def byName(name: String): ZLT[HtmlElement] = {
    val elements = inner.getElementsByAttributeValue("name", name)
    elements.size() match {
      case 0 => ZIO.fail(BotError(HtmlDocumentError, s"Elemento com nome '$name' não encontrado na página"))
      case 1 => ZIO.succeed(DefaultHtmlElement(charset, elements.get(0)))
      case n => ZIO.fail(BotError(HtmlDocumentError, s"Múltiplos elementos com o mesmo nome '$name' foram encontrados"))
    }
  }

  override def select(expression: String): ZLT[Seq[HtmlElement]] = {

    def check(elements: Seq[Element]): Task[Unit] = {
      if(elements == null) ZIO.fail(new Exception(s"Element HTML '${expression}' não encontrado"))
      else ZIO.unit
    }

    (for {
      elements  <- ZIO.attempt(inner.select(expression))
      _         <- check(elements.asScala.toSeq)
      converted <- ZIO.foreach(elements.asScala) { doc => ZIO.succeed(DefaultHtmlElement(charset, doc)) }
    } yield converted.toSeq).mapError(e => BotError(UnexpectedError, "Falha ao selecionar elemento da página", Some(e)))
  }

  override def attrOpt(name: String): ZLT[Option[String]] =  {
    if (inner.hasAttr(name)) {
      val value = inner.attr(name)
      if (value == null || value == "") ZIO.succeed(None) else ZIO.succeed(Some(value))
    } else {
      ZIO.succeed(None)
    }
  }
  override def attr(name: String): ZLT[String] = {
    for {
      opt   <- attrOpt(name)
      value <- opt match {
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.fail(BotError(HtmlDocumentError, s"atributo '$name' não existe"))
      }
    } yield value
  }

  override def text: ZLT[String] = ZIO.attempt(inner.text()).mapError(e => BotError(HtmlDocumentError, "Erro ao extrair o texto do elemento HTML", Some(e)))

  override def html: ZLT[String] = ZIO.attempt(inner.html()).mapError(e => BotError(HtmlDocumentError, "Erro ao extrair o html do elemento HTML", Some(e)))

  override def fnOpt[T](id: String)(fn: HtmlElement => ZLT[T]): ZLT[Option[T]] = {
    for {
      opt    <- byIdOpt(id)
      result <- opt match {
        case None     => ZIO.succeed(None)
        case Some(el) =>
          for {
            result <- fn(el)
          } yield Some(result)
      }
    } yield result
  }
}

case class DefaultHttpRequest (
  url             : String,
  ua              : String,
  name            : Option[String]            = None,
  method          : HttpMethod                = HttpMethod.Get,
  formEncoding    : FormEncoding              = FormEncoding.Data,
  followRedirects : Boolean                   = true,
  parameters      : Map[String, Set[String]]  = Map.empty, /* query string */
  headers         : Map[String, Set[String]]  = Map.empty,
  cookies         : Set[RequestCookie]        = Set.empty,
  fields          : Map[String, Set[String]]  = Map.empty,
  certificate     : Option[ClientCertificate] = None,
  version         : Option[HttpVersion]       = None,
  body            : HttpBody                  = NoBody) extends HttpRequest, ExecutableHttpRequest {

  private def update(map: Map[String, Set[String]])(name: String, value: String) = {
    map.updatedWith(name) {
      case Some(values) => Some(values + value)
      case None         => Some(Set(value))
    }
  }

  override def named(name: String) : ExecutableHttpRequest = copy(name = Some(name))
  override def url(replace: String): HttpRequest = copy(url = replace)

  override def body            (body: HttpBody)              : HttpRequest = copy(body            = body)
  override def header          (name: String, value: String) : HttpRequest = copy(headers         = update(headers)   (name, value))
  override def param           (name: String, value: String) : HttpRequest = copy(parameters      = update(parameters)(name, value))
  override def field           (name: String, value: String) : HttpRequest = copy(fields          = update(fields)    (name, value))
  override def cookie          (name: String, value: String) : HttpRequest = copy(cookies         = cookies + DefaultRequestCookie(name, value))
  override def cookies         (values: Map[String, String]) : HttpRequest = copy(cookies         = cookies ++ values.map(DefaultRequestCookie(_, _)))
  override def formEncoding    (formEncoding: FormEncoding)  : HttpRequest = copy(formEncoding    = formEncoding)
  override def followRedirects (follow: Boolean)             : HttpRequest = copy(followRedirects = follow)
  override def certificate     (cert: ClientCertificate)     : HttpRequest = copy(certificate     = Some(cert))
  override def version         (ver: HttpVersion)            : HttpRequest = copy(version         = Some(ver))


  override def get    (expectations: Expectation*) (using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, Some(HttpMethod.Get) )
  override def post   (expectations: Expectation*) (using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, Some(HttpMethod.Post))
  override def execute(expectations: Expectation*) (using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, None                 )

  override def body[T](body: T)(using enc: JsonEncoder[T]) : ZLT[HttpRequest] = {
    body.toJsonAST match
      case Left(err)  => ZIO.fail(BotError.of(UnexpectedError, "Erro ao serializar o corpo da requisição")(Exception(err)))
      case Right(ast) => ZIO.succeed(this.body(JsonBody(ast)))
  }

  private def exec(expectations: Seq[Expectation], method: Option[HttpMethod])(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = {

    def handleRedirect(response: HttpResponse): ZLT[HttpResponse] = {

      def follow: ZLT[HttpResponse] = {

        def fixRelativeUrl(location: String): ZLT[String] = {
          if(location.startsWith("..")) { //FIXME: not sure about this
            ZIO.attempt(new URI(url).resolve(new URI(HttpUtils.sanitize(location))).normalize().toString).mapError(BotError.of(Outcome.HttpError, s"Error normalizing relative redirect '${location}'"))
          } else {
            ZIO.succeed(location)
          }
        }

        for {
          location <- response.redirect
          loc      <- fixRelativeUrl(location)
          req      <- session.requestGiven(loc)
          res      <- req.named(s"FR-${name.getOrElse("_")}").get()
        } yield res
      }

      if(response.code == 302 && request.followRedirects)
        for {
          _      <- interceptor.onFollow(this, response)
          result <- follow
        } yield result

      else ZIO.succeed(response)
    }

    def request = method match
      case Some(m) => copy(method = m)
      case None    => this

    for {
      res1 <- engine.execute(request)
      _    <- session.update(request, res1)
      res2 <- handleRedirect(res1) //TODO prevent infinite redirects
      _    <- ZIO.foreach(expectations)(_.assert(request, res2))
      res3 <- interceptor.handle(this, res2)
    } yield res3
  }
}

case class DefaultHtmlForm(element: HtmlElement, method: String, action: String, values: Map[String, String]) extends HtmlForm {

  override def tag                        : ZLT[String]              = element.tag
  override def is(tag: String)            : ZLT[Boolean]             = element.is(tag)
  override def byId(id: String)           : ZLT[HtmlElement]         = element.byId(id)
  override def byIdOpt(id: String)        : ZLT[Option[HtmlElement]] = element.byIdOpt(id)
  override def byName(name: String)       : ZLT[HtmlElement]         = element.byName(name)
  override def select(expression: String) : ZLT[Seq[HtmlElement]]    = element.select(expression)
  override def attr(name: String)         : ZLT[String]              = element.attr(name)
  override def attrOpt(name: String)      : ZLT[Option[String]]      = element.attrOpt(name)
  override def text                       : ZLT[String]              = element.text
  override def html                       : ZLT[String]              = element.html

  override def fnOpt[T](id: String)(fn: HtmlElement => ZLT[T]): ZLT[Option[T]] = element.fnOpt(id)(fn)

  override def mergeValues(replacement: Map[String, String]): ZLT[HtmlForm] = {

//    def process(inputs: Seq[HtmlElement]): OIO[Unit] = {
//      for {
//        names <- ZIO.foreach(inputs) { input => input.attr("name").zip(input.attr("value")) }
//        _     <- ZIO.logInfo(names.mkString("\n"))
//      } yield ()
//    }
//
//    for {
//      inputs <- element.select("input")
//      names  <- ZIO.foreach(inputs) { _.attr("name") }
//    } yield copy(values = values ++ replacement.view.filterKeys(names.contains).toMap)

    ZIO.succeed(copy(values = values ++ replacement))
  }
}

object HtmlForm {
  def of(host: HtmlElement, id: String): ZLT[HtmlForm] = {
    for {
      element <- host.byId(id).orElseFail(BotError(HtmlDocumentError, s"Formulário '#$id' não encontrado"))
      form    <- HtmlForm.of(element)
    } yield form
  }

  def of(element: HtmlElement): ZLT[HtmlForm] = {

    def toMap(values: Seq[(Option[String], Option[String], Option[String], Option[String])]): Map[String, String] = {

      val seq = values map {
        case (Some("text")  , Some(n), v, _)               => Some((n, v.getOrElse("")))
        case (Some("hidden"), Some(n), v, _)               => Some((n, v.getOrElse("")))
        case (Some("radio") , Some(n), v, Some("checked")) => Some((n, v.getOrElse("")))
        case (None          , Some(n), v, _)               => Some((n, v.getOrElse("")))
        case _ => None
      } filter {
        _.isDefined
      } map {
        _.get
      }
      seq.toMap
    }

    for {
      tag    <- element.tag
      isForm <- element.is("form")
      _      <- ZIO.when(!isForm)(ZIO.fail(BotError(HtmlDocumentError, s"Elemento '$tag' não é um formulário")))
      method <- element.attr("method")
      action <- element.attr("action")
      enc    <- element.attrOpt("enctype")
      inputs <- element.select("input")
      pairs  <- ZIO.foreach(inputs) { input =>
        for {
          htype   <- input.attr("type")    .option
          name    <- input.attr("name")    .option
          value   <- input.attr("value")   .option
          checked <- input.attr("checked") .option
        } yield (htype, name, value, checked)
      }
    } yield DefaultHtmlForm(element, method, action, toMap(pairs))
  }
}

object Http {
  def layer: ZLayer[Any, Nothing, DefaultHttp] = ZLayer.fromFunction(DefaultHttp.apply _)
}

object Cookies {
  def from(cache: Seq[ResponseCookie]): Cookies = {
    val init = cache
      .filter(_.domain.isDefined)
      .toSet
      .map(cookie => (cookie.domain.get, cookie))
      .groupMap(_._1)(_._2)

    Cookies(init)
  }
}

case class Cookies(cache: Map[String, Set[ResponseCookie]]) {
  def updateDomain(domain: String, cookie: ResponseCookie, remove: Boolean): Cookies = {

    cache.get(domain) match
      case Some(set) if remove => copy(cache = cache + (domain -> (set.filterNot(_.name == cookie.name)         )))
      case Some(set)           => copy(cache = cache + (domain -> (set.filterNot(_.name == cookie.name) + cookie)))
      case None if !remove     => copy(cache = cache + (domain -> Set(cookie)))
      case _ => this
  }
}

case class DefaultHttp() extends Http {

  override def session(charset: Charset, baseUrl: String, ua: String, cookies: Cookies, proxy: Option[HttpProxy], certificate: Option[ClientCertificate])(using environment: HttpEnvironment): ZLT[HttpSession] = {
    for {
      counter <- Ref.make(0)
      cookies <- Ref.make(cookies)
      history <- Ref.make(Seq.empty)
    } yield DefaultHttpSession(
      counter,
      history,
      cookies,
      environment,
      charset,
      baseUrl,
      ua,
      proxy,
      certificate
    )
  }

  override def url(url: String)(using session: HttpSession): ZLT[HttpRequest] = {
    session.requestGiven(url)
  }

  override def requestGiven(form: HtmlForm)(using session: HttpSession): ZLT[HttpRequest] = {
    session.requestGiven(form)
  }
}

object JsonBody {
  def from[T](value: T)(using JsonEncoder[T]): ZLT[JsonBody] = {
    for {
      ast <- value.ast
    } yield JsonBody(ast)
  }
}

object HttpUtils {
  val iso  = Charset.forName("iso-8859-1")
  val utf8 = Charset.forName("utf8")

  def encodePath(path: String) = {
    path
      .replace(" ", "%20")
      .replace("\"", "%22")
      .replace("<", "%3C")
      .replace(">", "%3E")
      .replace("#", "%23")
      .replace("%", "%25")
      .replace("|", "%7C")
  }

  def sanitize(url: String) = {

    def fixQueryString(idx: Int) = {
      def fix(query: String) =
      query.split('&').map(_.split('=')).map({
        case Array(key)        => s"$key="
        case Array(key, value) => s"$key=${URLEncoder.encode(value, HttpUtils.iso)}"
      }).mkString("&")

      val query = url.substring(idx + 1)
      url.substring(0, idx) + "?" + fix(query)
    }

    val idx = url.indexOf("?")
    if (idx > 0) fixQueryString(idx) else url

  }

  def sanitizeX(url: String) = {
    val idx = url.indexOf("?")
    if (idx > 0) {
      val path  = url.substring(0, idx)
      val query = url.substring(idx + 1)
      encodePath(path) + "?" + URLEncoder.encode(query, HttpUtils.iso)
    } else {
      url
    }
  }

}

