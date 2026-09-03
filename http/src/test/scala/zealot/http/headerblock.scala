package zealot.http

import zealot.http.curl.HeaderBlock
import zio.test.*
import zio.test.Assertion.*

object HeaderBlockSpec extends ZIOSpecDefault {

  /** Recorte real de um '-D' do login do Eproc TJTO 1g com '--location', atrás de proxy:
    * bloco do túnel do proxy, o 307 do host de mTLS, e o 200 final, já em outro host, que é
    * quem planta os cookies da sessão do Keycloak. */
  val tjto = Seq(
    "HTTP/1.1 200 OK",
    "Date: Tue, 01 Sep 2026 19:55:18 GMT",
    "Transfer-Encoding: chunked",
    "",
    "HTTP/1.1 307 Temporary Redirect",
    "Server: nginx",
    "Content-Type: text/html",
    "Location: https://sso.tjto.jus.br/mtls?token=eyJhbGciOiJIUzI1NiJ9.abc&scope=openid",
    "",
    "HTTP/1.1 200 OK",
    "Server: nginx",
    "Content-Type: text/html;charset=utf-8",
    "Set-Cookie: AUTH_SESSION_ID=ZUQyWXAx;Version=1;Path=/realms/eproc/;Secure;HttpOnly;SameSite=None",
    "Set-Cookie: KC_AUTH_SESSION_HASH=O692T96A9Wmi;Version=1;Path=/realms/eproc/;Max-Age=60;Secure;SameSite=None",
  )

  val requested = "https://mtls.tjto.jus.br/realms/eproc/protocol/openid-connect/auth?scope=openid"

  def spec = suite("HeaderBlock Spec")(

    test("Splits one block per hop, proxy tunnel included") {
      val blocks = HeaderBlock.from(requested, tjto)
      assert(blocks.map(_.status))(equalTo(Seq(Some(200), Some(307), Some(200))))
    },

    test("A block with no Location does not move the chain") {
      // o túnel do proxy responde 200 sem 'Location': o 307 seguinte ainda é do host pedido
      val blocks = HeaderBlock.from(requested, tjto)
      assert(blocks(0).url)(equalTo(requested)) &&
      assert(blocks(1).url)(equalTo(requested))
    },

    test("Attributes the final response to the host that actually answered it") {
      // era isso que faltava: os cookies do 200 final são de sso.tjto.jus.br, não do host de mTLS
      val blocks = HeaderBlock.from(requested, tjto)
      assert(blocks(2).url)(startsWithString("https://sso.tjto.jus.br/mtls?token=")) &&
      assert(blocks(2).setCookies.map(_.takeWhile(_ != '=')))(equalTo(Seq("AUTH_SESSION_ID", "KC_AUTH_SESSION_HASH")))
    },

    test("Resolves a relative Location against the url of its own hop") {
      val lines = Seq(
        "HTTP/1.1 302 Found",
        "Location: externo_controlador.php?acao=SSO%2Flogin",
        "",
        "HTTP/1.1 200 OK",
        "Set-Cookie: PHPSESSID=abc",
      )
      val blocks = HeaderBlock.from("https://eproc1.tjto.jus.br/eprocV2_prod_1grau/", lines)
      assert(blocks(1).url)(equalTo("https://eproc1.tjto.jus.br/eprocV2_prod_1grau/externo_controlador.php?acao=SSO%2Flogin"))
    },

    test("A single response with no redirect keeps the requested url") {
      val lines  = Seq("HTTP/1.1 200 OK", "Set-Cookie: PHPSESSID=abc")
      val blocks = HeaderBlock.from(requested, lines)
      assert(blocks.map(_.url))(equalTo(Seq(requested)))
    },
  )
}
