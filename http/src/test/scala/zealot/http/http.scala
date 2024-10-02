package zealot.http

import zealot.commons.*
import org.jsoup.Jsoup
import org.jsoup.nodes.*
import zealot.commons.Outcome.HtmlDocumentError
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.Charset

object HttpSpec extends ZIOSpecDefault {

  val utf8 = Charset.forName("utf8")

  def build(html: String) = {
    DefaultHtmlElement(charset = utf8, inner = Jsoup.parse(html).selectFirst("form"))
  }

  def spec = suite("Http Spec")(

    test("Test Div") {
      val cause = Cause.fail(BotError(HtmlDocumentError, "Elemento 'div' não é um formulário"))
      val element = DefaultHtmlElement(charset = utf8, inner = Jsoup.parse("<div>").selectFirst("div"))
      for {
        exit <- HtmlForm.of(element).exit
      } yield assert(exit)(failsCause(containsCause(cause)))

    },

    test("Test Empty Form") {
      val element = build("""<form method="METHOD" action="ACTION"></form>""")
      for {
        form <- HtmlForm.of(element)
      } yield assert(form)(equalTo(DefaultHtmlForm(element, "METHOD", "ACTION", Map.empty)))
    },

    test("Test Form With Value") {
      val element = build("""<form method="METHOD" action="ACTION"><input name="N" value="V"/></form>""")
      for {
        form <- HtmlForm.of(element)
      } yield assert(form.values)(equalTo(Map("N" -> "V")))
    },

    test ("Test Bind Unknown Value") {
      val values = Map("FIELD" -> "VALUE")
      val element = build("""<form method="METHOD" action="ACTION"><input name="NAME"></form>""")
      for {
        form    <- HtmlForm.of(element)
        bounded <- form.mergeValues(values)
      } yield assert(bounded.values)(equalTo(values + ("NAME" -> "")))
    },

    test("Test Known Value") {
      val values = Map("NAME" -> "TANAKA", "FIELD" -> "VALUE")
      val element = build("""<form method="METHOD" action="ACTION"><input name="NAME"></form>""")
      for {
        form    <- HtmlForm.of(element)
        bounded <- form.mergeValues(values)
      } yield assert(bounded.values)(equalTo(values))
    },

    test ("Test Form With Value And Bind") {
      val values = Map("NAME" -> "TANAKA")
      val element = build("""<form method="METHOD" action="ACTION"><input name="NAME"/><input name="N" value="V"/></form>""")
      for {
        form    <- HtmlForm.of(element)
        bounded <- form.mergeValues(values)
      } yield assert(bounded.values)(equalTo(values + ("N" -> "V")))
    },

    test("Test Radio Inputs") {
      val element = build("""<form method="METHOD" action="ACTION"><input type="radio" name="RADIO" value="1"><input type="radio" name="RADIO" value="2" checked="checked"></form>""")
      for {
        form <- HtmlForm.of(element)
      } yield assert(form.values)(equalTo(Map("RADIO" -> "2")))
    }
  )
}