package fundertow.http

import java.util.Locale

import io.undertow.util.HttpString

/**
  * @see http://tools.ietf.org/html/rfc7231#section-4
  */
sealed abstract class HttpMethod(val value: String) {
  override def toString: String = value
}

object HttpMethod {

  case object OPTIONS extends HttpMethod("OPTIONS")
  case object GET extends HttpMethod("GET")
  case object HEAD extends HttpMethod("HEAD")
  case object POST extends HttpMethod("POST")
  case object PUT extends HttpMethod("PUT")
  case object DELETE extends HttpMethod("DELETE")
  case object CONNECT extends HttpMethod("CONNECT")
  case object TRACE extends HttpMethod("TRACE")
  case object PATCH extends HttpMethod("PATCH")
  final case class Other(override val value: String) extends HttpMethod(value)

  def fromString(str: HttpString): HttpMethod = str.toString.toUpperCase(Locale.ROOT) match {
    case OPTIONS.value => OPTIONS
    case GET.value => GET
    case HEAD.value => HEAD
    case POST.value => POST
    case PUT.value => PUT
    case DELETE.value => DELETE
    case TRACE.value => TRACE
    case CONNECT.value => CONNECT
    case PATCH.value => PATCH
    case s => Other(s)
  }
}

