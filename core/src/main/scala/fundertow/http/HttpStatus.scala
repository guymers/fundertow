/*
 * Based on https://github.com/http4s/http4s/blob/v0.20.0/core/src/main/scala/org/http4s/Status.scala
 *
 * Copyright http4s
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fundertow.http

/**
  * @see http://tools.ietf.org/html/rfc7231#section-6
  */
sealed abstract class HttpStatus private (
  val code: Int,
  val reason: String,
  val isEntityAllowed: Boolean
) {
  val `class`: HttpStatus.ResponseClass = HttpStatus.ResponseClass.fromStatus(code)

  override val hashCode: Int = code
  override def equals(obj: Any): Boolean = obj match {
    case o: HttpStatus => o.code == this.code
    case _ => false
  }
  override def toString: String = s"HttpStatus($code)"
}

object HttpStatus {

  def apply(code: Int, reason: String, isEntityAllowed: Boolean = true): HttpStatus =
    new HttpStatus(code, reason, isEntityAllowed) {}

  sealed trait ResponseClass {
    def isSuccess: Boolean
  }
  object ResponseClass {
    def fromStatus(code: Int): ResponseClass = {
      if (code < 200) Informational
      else if (code < 300) Successful
      else if (code < 400) Redirection
      else if (code < 500) ClientError
      else ServerError
    }
  }

  case object Informational extends ResponseClass { val isSuccess = true }
  case object Successful extends ResponseClass { val isSuccess = true }
  case object Redirection extends ResponseClass { val isSuccess = true }
  case object ClientError extends ResponseClass { val isSuccess = false }
  case object ServerError extends ResponseClass { val isSuccess = false }

  private val MaxCode = 599

  private object Registry {
    private val registry: Array[HttpStatus] = Array.ofDim(MaxCode + 1)

    def lookup(code: Int): Option[HttpStatus] = {
      if (code >= 0 && code <= MaxCode) Option(registry(code)) else None
    }

    def register(status: HttpStatus): HttpStatus = {
      registry(status.code) = status
      status
    }

    def all: List[HttpStatus] = registry.filter(_ != null).toList
  }

  import Registry.register

  val Continue: HttpStatus = register(HttpStatus(100, "Continue", isEntityAllowed = false))
  val SwitchingProtocols: HttpStatus = register(HttpStatus(101, "Switching Protocols", isEntityAllowed = false))
  val Processing: HttpStatus = register(HttpStatus(102, "Processing", isEntityAllowed = false))
  val EarlyHints: HttpStatus = register(HttpStatus(103, "Early Hints", isEntityAllowed = false))

  val Ok: HttpStatus = register(HttpStatus(200, "OK"))
  val Created: HttpStatus = register(HttpStatus(201, "Created"))
  val Accepted: HttpStatus = register(HttpStatus(202, "Accepted"))
  val NonAuthoritativeInformation: HttpStatus = register(HttpStatus(203, "Non-Authoritative Information"))
  val NoContent: HttpStatus = register(HttpStatus(204, "No Content", isEntityAllowed = false))
  val ResetContent: HttpStatus = register(HttpStatus(205, "Reset Content", isEntityAllowed = false))
  val PartialContent: HttpStatus = register(HttpStatus(206, "Partial Content"))
  val MultiStatus: HttpStatus = register(HttpStatus(207, "Multi-Status"))
  val AlreadyReported: HttpStatus = register(HttpStatus(208, "Already Reported"))
  val IMUsed: HttpStatus = register(HttpStatus(226, "IM Used"))

  val MultipleChoices: HttpStatus = register(HttpStatus(300, "Multiple Choices"))
  val MovedPermanently: HttpStatus = register(HttpStatus(301, "Moved Permanently"))
  val Found: HttpStatus = register(HttpStatus(302, "Found"))
  val SeeOther: HttpStatus = register(HttpStatus(303, "See Other"))
  val NotModified: HttpStatus = register(HttpStatus(304, "Not Modified", isEntityAllowed = false))
  val UseProxy: HttpStatus = register(HttpStatus(305, "Use Proxy"))
  val TemporaryRedirect: HttpStatus = register(HttpStatus(307, "Temporary Redirect"))
  val PermanentRedirect: HttpStatus = register(HttpStatus(308, "Permanent Redirect"))

  val BadRequest: HttpStatus = register(HttpStatus(400, "Bad Request"))
  val Unauthorized: HttpStatus = register(HttpStatus(401, "Unauthorized"))
  val PaymentRequired: HttpStatus = register(HttpStatus(402, "Payment Required"))
  val Forbidden: HttpStatus = register(HttpStatus(403, "Forbidden"))
  val NotFound: HttpStatus = register(HttpStatus(404, "Not Found"))
  val MethodNotAllowed: HttpStatus = register(HttpStatus(405, "Method Not Allowed"))
  val NotAcceptable: HttpStatus = register(HttpStatus(406, "Not Acceptable"))
  val ProxyAuthenticationRequired: HttpStatus = register(HttpStatus(407, "Proxy Authentication Required"))
  val RequestTimeout: HttpStatus = register(HttpStatus(408, "Request Timeout"))
  val Conflict: HttpStatus = register(HttpStatus(409, "Conflict"))
  val Gone: HttpStatus = register(HttpStatus(410, "Gone"))
  val LengthRequired: HttpStatus = register(HttpStatus(411, "Length Required"))
  val PreconditionFailed: HttpStatus = register(HttpStatus(412, "Precondition Failed"))
  val PayloadTooLarge: HttpStatus = register(HttpStatus(413, "Payload Too Large"))
  val UriTooLong: HttpStatus = register(HttpStatus(414, "URI Too Long"))
  val UnsupportedMediaType: HttpStatus = register(HttpStatus(415, "Unsupported Media Type"))
  val RangeNotSatisfiable: HttpStatus = register(HttpStatus(416, "Range Not Satisfiable"))
  val ExpectationFailed: HttpStatus = register(HttpStatus(417, "Expectation Failed"))
  val MisdirectedRequest: HttpStatus = register(HttpStatus(421, "Misdirected Request"))
  val UnprocessableEntity: HttpStatus = register(HttpStatus(422, "Unprocessable Entity"))
  val Locked: HttpStatus = register(HttpStatus(423, "Locked"))
  val FailedDependency: HttpStatus = register(HttpStatus(424, "Failed Dependency"))
  val TooEarly: HttpStatus = register(HttpStatus(425, "Too Early"))
  val UpgradeRequired: HttpStatus = register(HttpStatus(426, "Upgrade Required"))
  val PreconditionRequired: HttpStatus = register(HttpStatus(428, "Precondition Required"))
  val TooManyRequests: HttpStatus = register(HttpStatus(429, "Too Many Requests"))
  val RequestHeaderFieldsTooLarge: HttpStatus = register(HttpStatus(431, "Request Header Fields Too Large"))
  val UnavailableForLegalReasons: HttpStatus = register(HttpStatus(451, "Unavailable For Legal Reasons"))

  val InternalServerError: HttpStatus = register(HttpStatus(500, "Internal Server Error"))
  val NotImplemented: HttpStatus = register(HttpStatus(501, "Not Implemented"))
  val BadGateway: HttpStatus = register(HttpStatus(502, "Bad Gateway"))
  val ServiceUnavailable: HttpStatus = register(HttpStatus(503, "Service Unavailable"))
  val GatewayTimeout: HttpStatus = register(HttpStatus(504, "Gateway Timeout"))
  val HttpVersionNotSupported: HttpStatus = register(HttpStatus(505, "HTTP Version not supported"))
  val VariantAlsoNegotiates: HttpStatus = register(HttpStatus(506, "Variant Also Negotiates"))
  val InsufficientStorage: HttpStatus = register(HttpStatus(507, "Insufficient Storage"))
  val LoopDetected: HttpStatus = register(HttpStatus(508, "Loop Detected"))
  val NotExtended: HttpStatus = register(HttpStatus(510, "Not Extended"))
  val NetworkAuthenticationRequired: HttpStatus = register(HttpStatus(511, "Network Authentication Required"))
}
