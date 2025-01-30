package zealot.http

import better.files.*
import org.scalatest.*
import org.scalatest.flatspec.*
import org.scalatest.matchers.*
import zealot.http.curl.ResponseParser
import zio.{FiberFailure, Unsafe}

class CurlTest extends AnyFlatSpec with should.Matchers {

  val emptyBody: File = withFile("cases/empty.html") { identity }

  def withFile[T](path: String)(fn: File => T): T = {
    Resource.url(path).flatMap(_.toFile) match
      case None       => fail(s"can't load file from '$path'")
      case Some(file) => fn(file)
  }

  it should "throw and error if the http response code is missing" in {
    val task = withFile("cases/no.response.code") { file =>
      ResponseParser.parse(
        "",
        file,
        emptyBody,
        ""
      )
    }

    assertThrows[FiberFailure] {
      Unsafe.unsafe { implicit unsafe => zio.Runtime.default.unsafe.run(task).getOrThrowFiberFailure() }
    }
  }

  it should "parse a standard response with headers" in {
    val task = withFile("cases/standard.with.headers") { file =>
      ResponseParser.parse(
        "",
        file,
        emptyBody,
        ""
      )
    }

    val response = Unsafe.unsafe { implicit unsafe => zio.Runtime.default.unsafe.run(task).getOrThrowFiberFailure() }
    assert(response.code == 200)
    assert(response.headers.size == 1)
    assert(response.headers("x").head == "y")
  }

  it should "parse a standard response without headers" in {
    val task = withFile("cases/standard.no.headers") { file =>
      ResponseParser.parse(
        "",
        file,
        emptyBody,
        ""
      )
    }

    val response = Unsafe.unsafe { implicit unsafe => zio.Runtime.default.unsafe.run(task).getOrThrowFiberFailure() }
    assert(response.code == 200)
    assert(response.headers.size == 0)
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

  it should "parse file with multiple responses with headers" in {
    val task = withFile("cases/20250130104804G9ScdZmOqDxJX5kahDM9baOm8GzIQKmA3hdTZJ0UcHtajJy9lv/resp-0-login.headers") { file =>
      ResponseParser.parse(
        "",
        file,
        emptyBody,
        ""
      )
    }

    val response = Unsafe.unsafe { implicit unsafe => zio.Runtime.default.unsafe.run(task).getOrThrowFiberFailure() }
    assert(response.code == 200)
    assert(response.headers.size == 8)
    assert(response.headers("vary").head == "Accept-Encoding")
    assert(response.headers("content-length").head == "6200")
  }
}
