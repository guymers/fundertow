package fundertow.http

import io.undertow.util.HttpString
import io.undertow.util.Protocols

/**
  * @see http://tools.ietf.org/html/rfc7230#section-2.6
  */
sealed abstract case class HttpVersion(major: Int, minor: Int) {
  override def toString: String = s"HTTP/$major.$minor"
}

object HttpVersion {
  val `HTTP/0.9`: HttpVersion = new HttpVersion(0, 9) {}
  val `HTTP/1.0`: HttpVersion = new HttpVersion(1, 0) {}
  val `HTTP/1.1`: HttpVersion = new HttpVersion(1, 1) {}
  val `HTTP/2.0`: HttpVersion = new HttpVersion(2, 0) {}

  def fromString(protocol: HttpString): Option[HttpVersion] = protocol match {
    case Protocols.HTTP_0_9 => Some(`HTTP/0.9`)
    case Protocols.HTTP_1_0 => Some(`HTTP/1.0`)
    case Protocols.HTTP_1_1 => Some(`HTTP/1.1`)
    case Protocols.HTTP_2_0 => Some(`HTTP/2.0`)
    case _ => None
  }
}
