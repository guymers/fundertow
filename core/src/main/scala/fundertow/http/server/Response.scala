package fundertow.http.server

import fundertow.http.HttpHeaders
import fundertow.http.HttpStatus
import io.undertow.util.Headers

final case class Response[F[_]](
  status: HttpStatus,
  headers: HttpHeaders,
  body: F[Array[Byte]]
) {

  def withBody[T](t: T)(implicit E: ResponseBodyEncoder[F, T]): Response[F] = {
    val (contentType, contentLength, body) = E.encode(t)
    val headers = contentLength
      .fold(this.headers.remove(Headers.CONTENT_LENGTH))(l => this.headers.put(Headers.CONTENT_LENGTH, l.toString))
      .put(Headers.CONTENT_TYPE, contentType)
    copy(headers = headers, body = body)
  }

}
