package fundertow.http.server

import java.nio.charset.StandardCharsets

import fundertow.Id
import zio.Chunk

object ResponseBodyEncoder {

  implicit def stringUTF8F[F[_], C[_]](pure: Array[Byte] => F[C[Byte]]): ResponseBodyEncoder[F, C, String] = {
    new ResponseBodyEncoder[F, C, String] {
      override def encode(t: String): (String, Option[Long], F[C[Byte]]) = {
        val bytes = t.getBytes(StandardCharsets.UTF_8)

        ("text/plain; charset=UTF-8", Some(bytes.length.toLong), pure(bytes))
      }
    }
  }

  // orphans; see https://blog.7mind.io/no-more-orphans.html
  // FIXME test this
  implicit val stringUTF8ZIO: ResponseBodyEncoder[zio.ZIO[Any, Throwable, ?], Array, String] = {
    stringUTF8F(zio.ZIO.succeed(_))
  }

  implicit val stringUTF8ZStream: ResponseBodyEncoder[zio.stream.ZStream[Any, Throwable, ?], Id, String] = {
    // explict type for Scala 2.11
    stringUTF8F[zio.stream.ZStream[Any, Throwable, ?], Id](arr => zio.stream.ZStream.fromChunk(Chunk.fromArray(arr)))
  }
}

trait ResponseBodyEncoder[F[_], C[_], T] {
  def encode(t: T): (String, Option[Long], F[C[Byte]])
}
