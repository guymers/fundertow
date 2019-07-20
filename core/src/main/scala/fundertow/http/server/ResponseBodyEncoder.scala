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
  implicit val stringUTF8ZIO: ResponseBodyEncoder[zio.Task, String] = {
    stringUTF8F(zio.Task.succeed)
  }

  implicit val stringUTF8ZStream: ResponseBodyEncoder[zio.stream.ZStream[Any, Throwable, ?], String] = {
    // explict type for Scala 2.11
    stringUTF8F[zio.stream.ZStream[Any, Throwable, ?]](zio.stream.ZStream.succeed)
  }
}

trait ResponseBodyEncoder[F[_], T] {
  def encode(t: T): (String, Option[Long], F[Array[Byte]])
}
