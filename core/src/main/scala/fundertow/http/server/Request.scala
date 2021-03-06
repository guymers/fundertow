package fundertow.http.server

import fundertow.http.HttpHeaders
import fundertow.http.HttpMethod
import fundertow.http.HttpVersion

final case class Request[F[_], C[_]](
  version: HttpVersion,
  isSecure: Boolean,
  uri: String,
  method: HttpMethod,
  headers: HttpHeaders,
  body: F[C[Byte]]
)
