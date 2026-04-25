package zealot.http

import org.scalatest.*
import flatspec.*
import matchers.*
import zealot.http.*

import java.time.{ZoneId, ZonedDateTime}
import scala.util.{Failure, Success}

class CookieTest extends AnyFlatSpec with should.Matchers {

  def test(value: String)(fn: ResponseCookie => Unit) = {
    DefaultCookie.from("", value) match {
      case Failure(cause)  => fail(cause)
      case Success(cookie) => fn(cookie)
    }
  }

  it should "parse NAME" in {
    test("NAME") { cookie =>
      cookie.name shouldBe "NAME"
      cookie.value shouldBe ""
    }
  }

  it should "parse NAME=VALUE" in {
    test("NAME=VALUE") { cookie =>
      cookie.name  shouldBe "NAME"
      cookie.value shouldBe "VALUE"
    }
  }

  it should "parse NAME=" in {
    test("NAME=") { cookie =>
      cookie.name shouldBe "NAME"
      cookie.value shouldBe ""
    }
  }

  it should "parse NAME=VALUE=" in {
    test("NAME=VALUE=") { cookie =>
      cookie.name shouldBe "NAME"
      cookie.value shouldBe "VALUE="
    }
  }

  it should "parse NAME=VALUE==" in {
    test("NAME=VALUE==") { cookie =>
      cookie.name shouldBe "NAME"
      cookie.value shouldBe "VALUE=="
    }
  }

  it should "parse NAME=VALUE; Domain=DOMAIN" in {
    test("NAME=VALUE; Domain=DOMAIN") { cookie =>
      cookie.name   shouldBe "NAME"
      cookie.value  shouldBe "VALUE"
      cookie.domain shouldBe Some("DOMAIN")
    }
  }

  it should "parse NAME=VALUE; Domain=DOMAIN; Secure; HttpOnly" in {
    test("NAME=VALUE; Domain=DOMAIN; Secure; HttpOnly") { cookie =>
      cookie.name     shouldBe "NAME"
      cookie.value    shouldBe "VALUE"
      cookie.domain   shouldBe Some("DOMAIN")
      cookie.secure   shouldBe Some(true)
      cookie.httpOnly shouldBe Some(true)
    }
  }

  it should "parse NAME=VALUE; Expires=DATE" in {
    test("NAME=VALUE; Expires=Wed, 21 Oct 2015 07:28:00 GMT") { cookie =>
      cookie.name shouldBe "NAME"
      cookie.value shouldBe "VALUE"
      cookie.expires shouldBe Some(ZonedDateTime.of(2015, 10, 21, 7, 28, 0, 0, ZoneId.of("Z")))
    }
  }

  it should "parse date alternative format" in {
    test("KEYCLOAK_LOCALE=; Version=1; Comment=Expiring cookie; Expires=Thu, 01-Jan-1970 00:00:10 GMT; Max-Age=0; Path=/auth/realms/pje/; Secure; HttpOnly") { cookie =>
      cookie.name     shouldBe "KEYCLOAK_LOCALE"
      cookie.value    shouldBe ""
      cookie.expires  shouldBe Some(ZonedDateTime.of(1970, 1, 1, 0, 0, 10, 0, ZoneId.of("Z")))
      cookie.path     shouldBe Some("/auth/realms/pje/")
      cookie.maxAge   shouldBe Some(0)
      cookie.secure   shouldBe Some(true)
      cookie.httpOnly shouldBe Some(true)
    }
  }
}
