package zealot.http

import zio.test.*
import zio.test.Assertion.*

import java.net.URI

object UrlSpec extends ZIOSpecDefault {

  /** Same composition used by DefaultHttpRequest.fixRelativeUrl for a non-absolute location */
  def resolve(base: String, location: String) =
    new URI(base).resolve(new URI(HttpUtils.escapeIllegal(location))).normalize().toString

  def spec = suite("Url Spec")(

    test("Keeps an existing percent-escape in a relative location") {
      // Re-encoding '%2F' to '%252F' made eproc1g.tjrj.jus.br answer with the same
      // redirect forever, since it routes on 'acao'
      val location = "externo_controlador.php?acao=SSO%2Flogin&num_processo_bi=&lista_processos="
      assert(resolve("https://eproc1g.tjrj.jus.br/eproc/", location))(
        equalTo("https://eproc1g.tjrj.jus.br/eproc/externo_controlador.php?acao=SSO%2Flogin&num_processo_bi=&lista_processos=")
      )
    },

    test("Escapes spaces and accents so that a raw location still parses") {
      // eproc1g.tjms.jus.br sends the error message unencoded. Escaped as ISO-8859-1
      // for parity with the URLEncoder call this replaced
      val location = "externo_controlador.php?acao=principal&msg=Não foi localizado usuário com este CPF (12345678900)"
      assert(resolve("https://eproc1g.tjms.jus.br/eproc/controlador.php", location))(
        equalTo("https://eproc1g.tjms.jus.br/eproc/externo_controlador.php?acao=principal&msg=N%E3o%20foi%20localizado%20usu%E1rio%20com%20este%20CPF%20(12345678900)")
      )
    },

    test("Keeps base64 padding in a query value") {
      val location = "cb.php?state=abc&token=eyJhbGc.eyJzdWI9.sig=="
      assert(resolve("https://x.jus.br/app/", location))(
        equalTo("https://x.jus.br/app/cb.php?state=abc&token=eyJhbGc.eyJzdWI9.sig==")
      )
    },

    test("Keeps a value carrying its own '='") {
      val location = "r.php?next=a=b=c"
      assert(resolve("https://x.jus.br/app/", location))(
        equalTo("https://x.jus.br/app/r.php?next=a=b=c")
      )
    },

    test("Escapes a bare '%' that is not a valid escape") {
      val location = "r.php?desconto=50%&ok=1"
      assert(resolve("https://x.jus.br/app/", location))(
        equalTo("https://x.jus.br/app/r.php?desconto=50%25&ok=1")
      )
    },

    test("Resolves an absolute path location") {
      val location = "/eproc/login.php?acao=SSO%2Flogin"
      assert(resolve("https://x.jus.br/eproc/controlador.php", location))(
        equalTo("https://x.jus.br/eproc/login.php?acao=SSO%2Flogin")
      )
    },

    test("Resolves a dot-segment location") {
      val location = "../login.php?a=1"
      assert(resolve("https://x.jus.br/eproc/sub/controlador.php", location))(
        equalTo("https://x.jus.br/eproc/login.php?a=1")
      )
    },

    test("Leaves an already valid location untouched") {
      val location = "externo_controlador.php?acao=principal"
      assert(HttpUtils.escapeIllegal(location))(equalTo(location))
    },
  )
}
