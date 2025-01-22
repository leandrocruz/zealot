package zealot.http

import better.files.*
import org.scalatest.*
import org.scalatest.flatspec.*
import org.scalatest.matchers.*
import zealot.http.curl.ResponseParser
import zio.Unsafe

class CurlTest extends AnyFlatSpec with should.Matchers {

  val emptyBody: File = withFile("cases/empty.html") { identity }

  def withFile[T](path: String)(fn: File => T): T = {
    Resource.url(path).flatMap(_.toFile) match
      case None       => fail(s"can't load file from '$path'")
      case Some(file) => fn(file)
  }

  it should "parse file with multiple responses" in {
    val task = withFile("cases/20250122132712t8QzWl5RkWbxQ90uG9bfFGeAXmRYzGIKaMJdkHm338A9hjXsLB/resp-1-FR-login.headers") { file =>
      ResponseParser.parse(
        "https://eproc.trf4.jus.br/eproc2trf4/lib/priv/login_cert.php?acao_origem=",
        file,
        emptyBody,
        ""
      )
    }

    val response = Unsafe.unsafe { implicit unsafe => zio.Runtime.default.unsafe.run(task).getOrThrowFiberFailure() }
    assert(response.code == 302)
    assert(response.headers.size == 13)
    assert(response.headers("date").head == "Wed, 22 Jan 2025 16:27:16 GMT")
    assert(response.headers("x-powered-by").head == "PHP/8.2.13")
    assert(response.headers("content-type").head == "text/html; charset=ISO-8859-1")
  }

}
