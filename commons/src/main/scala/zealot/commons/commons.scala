package zealot.commons

import zio.*
import zio.json.*
import zio.json.ast.Json
import better.files.*
import File.*

enum Outcome:
    case AuthIsNotWorkingPleaseRetry
    case CaptchaError
    case CriticalError
    case InvalidCredentials
    case HtmlDocumentError
    case HttpError
    case ProxyError
    case SiteHasChanged
    case SiteError
    case Timeout
    case UnexpectedError

sealed trait BotResult
case class Success[T](value: T) extends BotResult
case class BotError(outcome: Outcome, explanation: String, cause: Option[Throwable] = None) extends BotResult {
    def as(outcome: Outcome, explanation: String) = copy(outcome = outcome, explanation = explanation)
}

object BotError {
    def of(outcome: Outcome, explanation: String)(cause: Throwable) = BotError(outcome, explanation, Some(cause))
    def of(cause: Throwable) = BotError(Outcome.UnexpectedError, cause.getMessage, Some(cause))
    def from(explanation: String)(other: BotError) = other.copy(explanation = explanation)
}

type ZLT[+A] = ZIO[Any, BotError, A]

object ZLT {

    import zealot.commons.Outcome.SiteHasChanged

    def some[T](maybe: Option[T])(message: => String): ZLT[T] = {
        maybe match
            case None        => ZIO.fail(BotError(SiteHasChanged, message))
            case Some(value) => ZIO.succeed(value)
    }

    def either[T](it: Either[Throwable, T]): ZLT[T] = {
        it match
            case Left(err)    => ZIO.fail(BotError.of(err))
            case Right(value) => ZIO.succeed(value)
    }
}

extension [T](t: T) {
    def ast(using enc: JsonEncoder[T]): ZLT[Json] = {
        t.toJsonAST match
            case Right(ast) => ZIO.succeed(ast)
            case Left(msg)  => ZIO.fail(BotError.of(Outcome.CriticalError, "Erro ao serializar o corpo da requisição em JSON")(Exception(msg)))
    }
}
