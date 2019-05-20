package fundertow.http.server

import java.nio.charset.StandardCharsets

object ResponseBodyEncoder {

  implicit def stringUTF8F[F[_]](pure: Array[Byte] => F[Array[Byte]]): ResponseBodyEncoder[F, String] = {
    new ResponseBodyEncoder[F, String] {
      override def encode(t: String): (String, Option[Long], F[Array[Byte]]) = {
        val bytes = t.getBytes(StandardCharsets.UTF_8)

        ("text/plain; charset=UTF-8", Some(bytes.length.toLong), pure(bytes))
      }
    }
  }

  // orphans; see https://blog.7mind.io/no-more-orphans.html
  // FIXME test this
  implicit val stringUTF8ZIO: ResponseBodyEncoder[scalaz.zio.Task, String] = {
    stringUTF8F(scalaz.zio.Task.succeed)
  }

  implicit val stringUTF8ZStream: ResponseBodyEncoder[scalaz.zio.stream.ZStream[Any, Throwable, ?], String] = {
    stringUTF8F(scalaz.zio.stream.ZStream.succeed)
  }
}

trait ResponseBodyEncoder[F[_], T] {
  def encode(t: T): (String, Option[Long], F[Array[Byte]])
}
