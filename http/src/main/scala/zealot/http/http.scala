package zealot.http

import better.files.File
import org.jsoup.Jsoup
import org.jsoup.nodes.*
import zealot.commons.*
import zealot.commons.Outcome.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.io.{BufferedInputStream, ByteArrayOutputStream, FileInputStream, InputStream}
import java.net.{URI, URL, URLEncoder}
import java.nio.charset.Charset
import java.util.zip.{GZIPInputStream, Inflater, InflaterInputStream}
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import scala.jdk.CollectionConverters.*
import scala.util.Try

trait HttpInterceptor {
  def handle  (request: ExecutableHttpRequest, response: HttpResponse): ZLT[HttpResponse] = ZIO.succeed(response)
  def onFollow(request: ExecutableHttpRequest, response: HttpResponse): ZLT[HttpResponse] = ZIO.succeed(response)
}

enum HttpMethod:
  case Head, Get, Post, Put, Delete

enum FormEncoding:
  case Data, DataRaw, DataBinary, DataUrlEncode, Multipart
  case DataUrlEncodeCharset(charset: Charset)

enum Compression:
  case Off
  case All
  case Only(algorithms: String*)

trait RequestCookie {
  def name    : String
  def value   : String
}

trait ResponseCookie extends RequestCookie {
  def url       : String // the request url the received this cookie as response
  def path      : Option[String]
  def domain    : Option[String]
  /**
   * Per RFC 6265 §5.3 step 6: a cookie set without a `Domain` attribute is
   * "host-only" — it must be sent only to the exact host that originated it,
   * never to subdomains. A cookie set with a `Domain` attribute is a "domain
   * cookie" and matches the domain plus all subdomains.
   *
   * The wire encodes this distinction by attribute presence/absence.
   * Storage formats (Netscape cookies.txt column 2, Chrome's cookies API
   * `domain` field) and matchers all need this bit. Folding it into
   * `domain: Option[String]` via leading-dot conventions silently broadens
   * cookie scope on round-trip — see the host-only/domain conflation that
   * caused our extension to create domain-cookie twins of host-only cookies
   * when calling `chrome.cookies.set` with a populated `domain` field.
   *
   * Reader/parser sites must set this explicitly. Default is `false` only
   * to keep older constructors compiling; new code should always be explicit.
   */
  def hostOnly  : Boolean
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
  def parent                     : ZLT[Option[HtmlElement]]
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
  def follow                                    : Boolean = code >= 300 && code < 400
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
case class PemClientCertificate(file: File)                              extends ClientCertificate
case class Pkcs12ClientCertificate(file: File, password: Option[String]) extends ClientCertificate

trait HttpRequest {
  def url               (replace: String)                    : HttpRequest
  def header            (name: String, value: String)        : HttpRequest
  def headers           (values: Map[String, Set[String]])   : HttpRequest
  def removeHeader      (name: String)                       : HttpRequest
  def param             (name: String, value: String)        : HttpRequest
  def cookie            (name: String, value: String)        : HttpRequest
  def cookies           (values: Map[String, String])        : HttpRequest
  def field             (name: String, value: String)        : HttpRequest
  def fields            (values: Map[String, Set[String]])   : HttpRequest
  def formEncoding      (encoding: FormEncoding)             : HttpRequest
  def suppressUserAgent                                      : HttpRequest
  def followRedirects   (follow: Boolean)                    : HttpRequest
  def maxRedirects      (max: Int)                           : HttpRequest
  def certificate       (cert: ClientCertificate)            : HttpRequest
  def version           (ver: HttpVersion)                   : HttpRequest
  def body              (body: HttpBody)                     : HttpRequest
  def body[T]           (body: T)(using enc: JsonEncoder[T]) : ZLT[HttpRequest]
  def compressed        (compression: Compression = Compression.All) : HttpRequest
  def named             (name: String)                       : ExecutableHttpRequest
}

trait HttpContext {
  def root: File
  def logger: HttpLogger
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
  def compressed      : Option[Compression]
  def formEncoding    : FormEncoding
  def followRedirects : Boolean
  def setUserAgent    : Boolean
  def certificate     : Option[ClientCertificate]
  def version         : Option[HttpVersion]
  def execute (                      expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
  def execute (options: HttpOptions, expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
  def get     (                      expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
  def get     (options: HttpOptions, expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
  def post    (                      expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
  def post    (options: HttpOptions, expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse]
}

trait HttpEngine {
  def execute(request: ExecutableHttpRequest, options: Option[HttpOptions] = None)(using ctx: HttpContext, session: HttpSession, trace: Trace): ZLT[HttpResponse]
}

sealed trait HttpEnvironment
case object Production                                     extends HttpEnvironment
case class Test(host: String, port: Int, scenario: String) extends HttpEnvironment

case class ProxyAuth(username: String, password: String)

case class HttpProxy(
  host                      : String,
  port                      : Int,
  auth                      : Option[ProxyAuth] = None,
  secure                    : Boolean           = false,
  skipCertificateValidation : Boolean           = true
)


trait HttpOptions
case class CurlOptions(binary: String) extends HttpOptions

trait HttpSession {
  def environment  : HttpEnvironment
  def charset      : Charset
  def baseUrl      : String
  def ua           : String
  def compressed   : Compression
  def certificate  : Option[ClientCertificate]
  def proxy        : Option[HttpProxy]
  def update(request: ExecutableHttpRequest, response: HttpResponse) : ZLT[Unit]
  def requestGiven(url: String   , version: Option[HttpVersion])     : ZLT[HttpRequest]
  def requestGiven(form: HtmlForm, version: Option[HttpVersion])     : ZLT[HttpRequest]
  def cookies                      : CookieJar
  def cookiesGiven(url: String)    : ZLT[Seq[ResponseCookie]]
  def count                        : ZLT[Int]
}

trait Http {
  def session(
    charset     : Charset,
    baseUrl     : String,
    ua          : String,
    headers     : Map[String, Set[String]]  = Map.empty,
    cookies     : Set[ResponseCookie]       = Set.empty,
    compressed  : Compression               = Compression.Off,
    proxy       : Option[HttpProxy]         = None,
    certificate : Option[ClientCertificate] = None,
  )(using HttpEnvironment, HttpContext) : ZLT[HttpSession]

  def url(url: String)            (using HttpSession): ZLT[HttpRequest]
  def requestGiven(form: HtmlForm)(using HttpSession): ZLT[HttpRequest]
}

trait Script

trait HttpLogger {
  def task(message: => String): Task[Unit]
  def zlt (message: => String): ZLT[Unit]
}

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
  hostOnly : Boolean               = false,
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
    Thu, 01-Jan-25 00:00:10 GMT
  */
  private val re1 = s"[a-zA-Z]{3}, (\\d{2}) ([a-zA-Z]{3}) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT".r
  private val re2 = s"[a-zA-Z]{3}, (\\d{2})\\-([a-zA-Z]{3})\\-(\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT".r
  private val re3 = s"[a-zA-Z]{3}, (\\d{2})\\-([a-zA-Z]{3})\\-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) GMT".r

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
        case re1(day, month, year, hour, minute, second) => ZonedDateTime.of(year.toInt       , toMonth(month), day.toInt, hour.toInt, minute.toInt, second.toInt, 0, ZoneId.of("Z"))
        case re2(day, month, year, hour, minute, second) => ZonedDateTime.of(year.toInt       , toMonth(month), day.toInt, hour.toInt, minute.toInt, second.toInt, 0, ZoneId.of("Z"))
        case re3(day, month, year, hour, minute, second) => ZonedDateTime.of(2000 + year.toInt, toMonth(month), day.toInt, hour.toInt, minute.toInt, second.toInt, 0, ZoneId.of("Z"))
        case _                                           => throw new Exception(s"Unparsable date '$value'")
    }

    def pairGiven(str: String) = {
      str.split("=", 2) match {
        case Array(name)        => (name, "")
        case Array(name, value) => (name, value)
        case _                  => throw new Exception(s"Can't extract name/value pair from '$str'")
      }
    }

    val str1 = if(dropDoubleQuotes && str0.startsWith("\"")) str0.drop(1)      else str0
    val str2 = if(dropDoubleQuotes && str1.endsWith("\""))   str1.dropRight(1) else str1
    val parsed = str2
      .split(";")
      .map(_.trim)
      .zipWithIndex
      .foldLeft(DefaultResponseCookie(url, "", "", hostOnly = true)) { (cookie, tuple) =>
        val slice = tuple._1
        val index = tuple._2
        if(index == 0) {
          val (name, value) = pairGiven(tuple._1)
          cookie.copy(name = name, value = value)
        } else {
          if(slice.contains("=")) {
            val (name, value) = pairGiven(slice)
            name.toLowerCase match {
              case "expires"  => cookie.copy(expires  = Some(parseDate(value)))
              // RFC 6265 §5.2.3: strip leading dot. §5.3 step 6: presence of
              // Domain attribute makes the cookie a domain cookie (hostOnly=false).
              case "domain"   => cookie.copy(domain   = Some(value.stripPrefix(".")), hostOnly = false)
              case "path"     => cookie.copy(path     = Some(value))
              case "max-age"  => cookie.copy(maxAge   = Some(value.toLong))
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

    // For host-only cookies (no Domain attribute), §5.3 step 6 sets cookie-domain
    // to the canonical request host. Populate `domain` so downstream matchers and
    // serializers don't have to re-derive it from `url`.
    if (parsed.hostOnly && parsed.domain.isEmpty)
      parsed.copy(domain = Try(new URI(url).getHost).toOption.filter(_ != null).map(_.toLowerCase))
    else parsed
  }
}

case class DefaultHttpSession(
  counter     : Ref[Int],
  history     : Ref[Seq[(ZonedDateTime, String)]],
  cookieJar   : CookieJar,
  environment : HttpEnvironment,
  charset     : Charset,
  baseUrl     : String, //TODO check if baseUrl is a valid, absolute url
  ua          : String,
  headers     : Map[String, Set[String]],
  compressed  : Compression               = Compression.Off,
  proxy       : Option[HttpProxy]         = None,
  certificate : Option[ClientCertificate] = None
) extends HttpSession {

  private def isAbsolute(url: String) = url.startsWith("http://") || url.startsWith("https://")

  private def domainGiven(url: String): ZLT[String] = {

    def error(tried: String, cause: Option[Throwable] = None) = BotError(HttpError, s"Error extraindo domínio de '$tried'", cause)

    def tryHost = for
      abs <- ZIO.attempt(new URL(url).toURI).mapError(e => error(url, Some(e)))
    yield abs.getHost

    if      isAbsolute(url)           then tryHost
    else if isAbsolute(baseUrl + url) then domainGiven(baseUrl + url)
    else                                   ZIO.fail(error(baseUrl + url))

  }

  private def cookiesByDomain(domain: String): ZLT[Set[ResponseCookie]] = {
    ZIO.succeed(cookieJar.byDomain(domain))
  }

  override def count: ZLT[Int] = counter.getAndUpdate(_ + 1)
  override def update(request: ExecutableHttpRequest, response: HttpResponse): ZLT[Unit] = {

    def update: Task[Unit] = {

      given HttpSession = this

      for
        now <- Clock.currentDateTime.map(_.toZonedDateTime)
        _   <- history.update(_ :+ (now, response.requestedUrl))
      yield ()
    }

    update.mapError(e => BotError(UnexpectedError, "Erro atualizando estado interno do navegador", Some(e)))
  }

  override def requestGiven(url: String, version: Option[HttpVersion]) = {
    for {
      domain  <- domainGiven(url)
      cookies <- cookiesByDomain(domain)
    } yield DefaultHttpRequest(
      url         = url,
      ua          = ua,
      headers     = headers,
      cookies     = cookies.map(_.toRequest),
      certificate = certificate,
      version     = version
    )
  }

  override def requestGiven(form: HtmlForm, version: Option[HttpVersion]) = {
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
        certificate = certificate,
        version     = version
      )
    }

    build.mapError(_.as(HtmlDocumentError, "Erro ao criar requisição baseada no formulário"))
  }

  override def cookies = cookieJar

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

    def isTextContent: Boolean = {
      header("content-type").map(_.trim.toLowerCase) match
        case None       => true
        case Some(ct)   =>
          ct.startsWith("text/")              ||
          ct.startsWith("application/json")   ||
          ct.startsWith("application/xml")    ||
          ct.startsWith("application/xhtml")  ||
          ct.contains("javascript")           ||
          ct.contains("+xml")                 ||
          ct.contains("+json")                ||
          ct.contains("x-www-form-urlencoded")
    }

    def read = {

      //Detect the actual compression from the first bytes of the body instead of trusting
      //the Content-Encoding header. curl's --compressed flag decompresses transparently but
      //leaves the original response headers untouched, so Content-Encoding may claim the body
      //is compressed when it is not. Magic bytes are the source of truth; Content-Encoding is
      //only consulted as a fallback for raw deflate, which has no magic bytes.
      def wrapperGiven(bis: BufferedInputStream): InputStream = {
        bis.mark(2)
        val b0 = bis.read()
        val b1 = bis.read()
        bis.reset()

        val isGzip = b0 == 0x1f && b1 == 0x8b
        val isZlib = b0 == 0x78 && ((b0 * 256 + b1) % 31) == 0

        //HTTP Content-Encoding: deflate is supposed to be zlib-wrapped (RFC 7230), but some
        //servers send raw deflate with no zlib header. Raw deflate has no reliable magic, so
        //trust the header only after magic-byte detection has ruled out gzip and zlib.
        def isRawDeflate = header("content-encoding").exists(_.trim.equalsIgnoreCase("deflate"))

        if      isGzip       then new GZIPInputStream(bis)                          // gzip magic
        else if isZlib       then new InflaterInputStream(bis)                      // zlib header
        else if isRawDeflate then new InflaterInputStream(bis, new Inflater(true))  // raw deflate (nowrap)
        else                      bis                                               // plain / unknown
      }

      def open                   = ZIO.attemptBlocking(new BufferedInputStream(new FileInputStream(body.toJava)))
      def close(is: InputStream) = ZIO.succeed(is.close())

      ZIO.acquireReleaseWith(open)(close) { bis =>
        ZIO.attemptBlocking {
          val is  = wrapperGiven(bis)
          val buf = new ByteArrayOutputStream()
          is.transferTo(buf)
          buf.toString(charset.map(_.name()).getOrElse("UTF-8"))
        }
      }
    }

    if isTextContent then
      read.mapError(BotError.of(HtmlDocumentError, "Error reading the response body on text mode"))
    else
      ZIO.fail(BotError(HtmlDocumentError, s"Response body is not readable text (Content-Type: ${header("content-type").getOrElse("unknown")})"))
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

  private def elFn[T](msg: String, outcome: Outcome = HtmlDocumentError)(fn: Element => T): ZLT[T] = ZIO.attempt(fn(inner)).mapError(e => BotError(outcome, msg, Some(e)))

  override def text   = elFn("Erro ao extrair o texto do elemento") { _.text() }
  override def html   = elFn("Erro ao extrair o html do elemento" ) { _.html() }
  override def parent = elFn("Erro ao extrair pai do elemento"    ) { el => Option(el.parent()).map(DefaultHtmlElement(charset, _)) }

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
  maxRedirects    : Int                       = 10,
  parameters      : Map[String, Set[String]]  = Map.empty, /* query string */
  headers         : Map[String, Set[String]]  = Map.empty,
  cookies         : Set[RequestCookie]        = Set.empty,
  fields          : Map[String, Set[String]]  = Map.empty,
  certificate     : Option[ClientCertificate] = None,
  setUserAgent    : Boolean                   = true,
  compressed      : Option[Compression]       = None,
  version         : Option[HttpVersion]       = None,
  body            : HttpBody                  = NoBody) extends HttpRequest, ExecutableHttpRequest {

  private def update(map: Map[String, Set[String]])(name: String, value: Option[String]): Map[String, Set[String]] = {
    map.updatedWith(name) {
      case Some(values) => value.map(v => values + v)
      case None         => value.map(v => Set(v))
    }
  }

  override def named(name: String) : ExecutableHttpRequest = copy(name = Some(name))
  override def url(replace: String): HttpRequest = copy(url = replace)

  override def body            (body: HttpBody)                   : HttpRequest = copy(body            = body)
  override def header          (name: String, value: String)      : HttpRequest = copy(headers         = update(headers)   (name, Some(value)))
  override def headers         (values: Map[String, Set[String]]) : HttpRequest = copy(headers         = headers ++ values)
  override def removeHeader    (name: String)                     : HttpRequest = copy(headers         = update(headers)   (name, None      ))
  override def param           (name: String, value: String)      : HttpRequest = copy(parameters      = update(parameters)(name, Some(value)))
  override def field           (name: String, value: String)      : HttpRequest = copy(fields          = update(fields)    (name, Some(value)))
  override def fields          (values: Map[String, Set[String]]) : HttpRequest = copy(fields          = fields ++ values)
  override def cookie          (name: String, value: String)      : HttpRequest = copy(cookies         = cookies + DefaultRequestCookie(name, value))
  override def cookies         (values: Map[String, String])      : HttpRequest = copy(cookies         = cookies ++ values.map(DefaultRequestCookie(_, _)))
  override def formEncoding    (formEncoding: FormEncoding)       : HttpRequest = copy(formEncoding    = formEncoding)
  override def suppressUserAgent                                  : HttpRequest = copy(setUserAgent    = false)
  override def followRedirects (follow: Boolean)                  : HttpRequest = copy(followRedirects = follow)
  override def maxRedirects    (max: Int)                         : HttpRequest = copy(maxRedirects    = max)
  override def certificate     (cert: ClientCertificate)          : HttpRequest = copy(certificate     = Some(cert))
  override def compressed      (compression: Compression)         : HttpRequest = copy(compressed      = Some(compression))
  override def version         (ver: HttpVersion)                 : HttpRequest = copy(version         = Some(ver))

  override def get    (                      expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, Some(HttpMethod.Get) , None         )
  override def get    (options: HttpOptions, expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, Some(HttpMethod.Get) , Some(options))
  override def post   (                      expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, Some(HttpMethod.Post), None         )
  override def post   (options: HttpOptions, expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, Some(HttpMethod.Post), Some(options))
  override def execute(                      expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, None                 , None         )
  override def execute(options: HttpOptions, expectations: Expectation*)(using ctx: HttpContext, session: HttpSession, captcha: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = exec(expectations, None                 , Some(options))

  override def body[T](body: T)(using enc: JsonEncoder[T]) : ZLT[HttpRequest] = {
    body.toJsonAST match
      case Left(err)  => ZIO.fail(BotError.of(UnexpectedError, "Erro ao serializar o corpo da requisição")(Exception(err)))
      case Right(ast) => ZIO.succeed(this.body(JsonBody(ast)))
  }

  private def exec(expectations: Seq[Expectation], method: Option[HttpMethod], options: Option[HttpOptions])(using ctx: HttpContext, session: HttpSession, interceptor: HttpInterceptor, engine: HttpEngine, trace: Trace): ZLT[HttpResponse] = {

    def handleRedirect(response: HttpResponse, count: Int): ZLT[HttpResponse] = {

      def follow(toFollow: HttpResponse): ZLT[HttpResponse] = {

        def fixRelativeUrl(location: String): ZLT[String] = {

          def handleRelative = {
            ZIO
              .attempt(new URI(url).resolve(new URI(HttpUtils.sanitize(location))).normalize().toString)
              .mapError(BotError.of(Outcome.HttpError, s"Error normalizing relative redirect '${location}'"))
          }

          (location.startsWith(".."), location.startsWith("/")) match
            case (true, _) => handleRelative
            case (_, true) => handleRelative
            case _         => ZIO.succeed(location)
        }

        for {
          _        <- ZIO.when(count >= maxRedirects) { ZIO.fail(BotError(HttpError, s"Too many redirects (max $maxRedirects) for url '${request.url}'")) }
          location <- toFollow.redirect
          loc      <- fixRelativeUrl(location)
          req      <- session.requestGiven(loc, version)
          res      <- req.named(s"FR-${name.getOrElse("_")}").get()
          _        <- session.update(request, res)
          result   <- handleRedirect(res, count + 1)
        } yield result
      }

      if(request.followRedirects && response.follow)
        for
          alternative <- interceptor.onFollow(this, response)
          result      <- if (alternative.follow) follow(alternative) else ZIO.succeed(alternative)
        yield result
      else ZIO.succeed(response)
    }

    def request = method match
      case Some(m) => copy(method = m)
      case None    => this

    for
      res1 <- engine.execute(request, options)
      _    <- session.update(request, res1)
      res2 <- handleRedirect(res1, 0)
      _    <- ZIO.foreach(expectations)(_.assert(request, res2))
      res3 <- interceptor.handle(this, res2)
    yield res3
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
  override def parent                     : ZLT[Option[HtmlElement]] = element.parent

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
  def layer: ZLayer[Any, Nothing, DefaultHttp] = ZLayer.fromFunction(() => DefaultHttp())
}

sealed trait CookieJar {
  def all                     : Set[ResponseCookie]
  def byDomain(domain: String): Set[ResponseCookie]
}

case class CurlCookieJar(file: File) extends CookieJar {

  override def all: Set[ResponseCookie]                      = NetscapeCookieFile.read(file)
  override def byDomain(domain: String): Set[ResponseCookie] = all.filter(c => NetscapeCookieFile.matches(domain, c))
}

/**
 * Reads and writes the Netscape "cookies.txt" file format used by curl's
 * --cookie / --cookie-jar flags.
 *
 * Format: 7 tab-separated columns per line.
 *   1. domain               (host or .domain.tld; "#HttpOnly_" prefix marks HttpOnly)
 *   2. include-subdomains   (TRUE for domain cookies, FALSE for host-only)
 *   3. path                 (defaults to "/")
 *   4. secure               (TRUE / FALSE)
 *   5. expires              (unix epoch seconds; 0 means session cookie)
 *   6. name
 *   7. value
 *
 * Lines starting with "#" are comments. Empty lines are ignored.
 *
 * See: https://curl.se/docs/http-cookies.html
 */
object NetscapeCookieFile {

  private val HttpOnlyPrefix = "#HttpOnly_"

  private val Header =
    """# Netscape HTTP Cookie File
      |# https://curl.se/docs/http-cookies.html
      |# This file was generated by zealot. Edit at your own risk.
      |""".stripMargin

  def read(file: File): Set[ResponseCookie] = {
    if (!file.exists) Set.empty
    else file.lineIterator
      .map(_.trim)
      .filter(line => line.nonEmpty)
      .flatMap(parseLine)
      .toSet
  }

  def write(file: File, cookies: Iterable[ResponseCookie]): Unit = {
    val body = cookies.map(formatLine).mkString("\n")
    file.overwrite(Header + body + (if (body.nonEmpty) "\n" else ""))
  }

  /**
   * RFC 6265 §5.1.3 host/domain matching driven by the `hostOnly` flag.
   * - hostOnly = true  : request host must equal cookie domain (case-insensitive).
   * - hostOnly = false : request host must equal OR be a subdomain of cookie domain.
   */
  def matches(requestHost: String, cookie: ResponseCookie): Boolean = {
    cookie.domain match {
      case None    => false
      case Some(d) =>
        val bare = d.stripPrefix(".").toLowerCase
        val host = requestHost.toLowerCase
        if (cookie.hostOnly) host == bare
        else                 host == bare || host.endsWith("." + bare)
    }
  }

  private def parseLine(line: String): Option[ResponseCookie] = {
    if (line.startsWith("#") && !line.startsWith(HttpOnlyPrefix)) None
    else {
      val cols = line.split('\t')
      if (cols.length < 7) None
      else {
        val (rawDomain, httpOnly) =
          if (cols(0).startsWith(HttpOnlyPrefix)) (cols(0).drop(HttpOnlyPrefix.length), true)
          else                                    (cols(0)                            , false)

        val includeSub = cols(1).equalsIgnoreCase("TRUE")
        val path       = cols(2)
        val secure     = cols(3).equalsIgnoreCase("TRUE")
        val expires    = Option(cols(4)).flatMap(s => Try(s.toLong).toOption).filter(_ > 0)
        val name       = cols(5)
        val value      = cols(6)

        // Column 2 carries the host-only-vs-domain distinction. Strip any
        // leading dot from column 1 so `domain` always holds the bare
        // canonical value (RFC 6265 §5.2.3 / §5.3 step 6).
        val bareDomain = rawDomain.stripPrefix(".")

        Some(DefaultResponseCookie(
          url      = "",
          name     = name,
          value    = value,
          domain   = Some(bareDomain),
          path     = Some(path),
          hostOnly = !includeSub,
          secure   = Some(secure),
          httpOnly = Some(httpOnly),
          expires  = expires.map(s => ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(s), ZoneId.of("UTC")))
        ))
      }
    }
  }

  private def formatLine(cookie: ResponseCookie): String = {

    val rawDomain  = cookie.domain.map(_.stripPrefix(".")).getOrElse(hostOf(cookie.url).getOrElse(""))
    val includeSub = !cookie.hostOnly
    val prefix     = if (cookie.httpOnly.contains(true)) HttpOnlyPrefix else ""
    val path       = cookie.path.getOrElse("/")
    val secure     = if (cookie.secure.contains(true)) "TRUE" else "FALSE"
    val includeStr = if (includeSub) "TRUE" else "FALSE"
    val expires    = cookie.expires.map(_.toEpochSecond.toString).getOrElse("0")

    Seq(
      prefix + rawDomain,
      includeStr,
      path,
      secure,
      expires,
      cookie.name,
      cookie.value
    ).mkString("\t")
  }

  private def hostOf(url: String): Option[String] = {
    if (url.isEmpty) None
    else Try(new URI(url).getHost).toOption.filter(_ != null)
  }
}

case class DefaultHttp() extends Http {

  override def session(
    charset     : Charset,
    baseUrl     : String,
    ua          : String,
    headers     : Map[String, Set[String]],
    cookies     : Set[ResponseCookie],
    compressed  : Compression,
    proxy       : Option[HttpProxy],
    certificate : Option[ClientCertificate])(using environment: HttpEnvironment, ctx: HttpContext): ZLT[HttpSession] = {

    def buildCookieJar: ZLT[CookieJar] = ZIO.attemptBlocking {
      val file = ctx.root / "cookies.txt"
      file.touch()
      NetscapeCookieFile.write(file, cookies)
      CurlCookieJar(file)
    }.mapError(BotError.of(UnexpectedError, s"Erro ao criar cookie jar"))

    for
      counter <- Ref.make(0)
      history <- Ref.make(Seq.empty[(ZonedDateTime, String)])
      jar     <- buildCookieJar
    yield DefaultHttpSession(
      counter,
      history,
      jar,
      environment,
      charset,
      baseUrl,
      ua,
      headers,
      compressed,
      proxy,
      certificate
    )
  }

  override def url(url: String)(using session: HttpSession): ZLT[HttpRequest] = {
    session.requestGiven(url, None)
  }

  override def requestGiven(form: HtmlForm)(using session: HttpSession): ZLT[HttpRequest] = {
    session.requestGiven(form, None)
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

