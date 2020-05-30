package fundertow.zio.channels

import java.nio.ByteBuffer

import fundertow.zio.util.ByteBufferProvider
import org.xnio.ChannelListener
import org.xnio.channels.StreamSourceChannel
import zio._
import zio.stream.ZStream

object StreamSourceChannelHelper {

  // TODO if (contentLength > maxBufferSize) error.error(exchange, new RequestToLargeException())
  /*
  io.undertow.server.RequestTooBigException: UT000020: Connection terminated as request was larger than 1048576
	at io.undertow.conduits.ChunkedStreamSourceConduit.updateRemainingAllowed(ChunkedStreamSourceConduit.java:135)
	at io.undertow.conduits.ChunkedStreamSourceConduit.read(ChunkedStreamSourceConduit.java:266)
	at org.xnio.conduits.ConduitStreamSourceChannel.read(ConduitStreamSourceChannel.java:127)
	at io.undertow.channels.DetachableStreamSourceChannel.read(DetachableStreamSourceChannel.java:209)
	at io.undertow.server.HttpServerExchange$ReadDispatchChannel.read(HttpServerExchange.java:2345)
	at fundertow.zio.channels.StreamSourceChannelHelper$.$anonfun$read$1(StreamSourceChannelHelper.scala:71)
   */

  def stream(channel: StreamSourceChannel): ZStream[ByteBufferProvider, Throwable, Byte] = {
    ZStream {
      setup(channel).catchAll { t =>
        ZManaged.effectTotal(ZIO.fail(Some(t)))
      }
    }
  }

  private def setup(channel: StreamSourceChannel) = for {
    buffer <- ByteBufferProvider.Service.allocate
    done <- ZRef.makeManaged(false)
    paused <- Queue.sliding[Unit](1).toManaged_
    runtime <- ZIO.runtime[Any].toManaged_
    _ <- ZIO.effect {
      channel.getCloseSetter.set(new ChannelListener[StreamSourceChannel] {
        override def handleEvent(channel: StreamSourceChannel): Unit = {
          println(s"${Thread.currentThread().getName} channel.getCloseSetter handleEvent")
          runZIO(runtime) {
            done.set(true)
          }
        }
      })

      channel.getReadSetter.set(new ChannelListener[StreamSourceChannel] {
        override def handleEvent(channel: StreamSourceChannel): Unit = {
          println(s"${Thread.currentThread().getName} channel.getReadSetter handleEvent")
          runZIO(runtime) {
            done.get.flatMap {
              case true => ZIO.unit
              case false => paused.offer(()).as(())
            }
          }
        }
      })
    }.toManaged_
    _ <- paused.offer(()).toManaged_
  } yield {
    done.get.flatMap {
      case true => ZIO.fail(None)
      case false => paused.take *> read(channel, buffer).tap { chunk =>
        if (chunk.isEmpty) {
          ZIO.effect(channel.resumeReads()).mapError(Some(_))
        } else {
          paused.offer(())
        }
      }
    }
  }

  // see io.undertow.io.AsyncReceiverImpl.receivePartialBytes
  private def read(
    channel: StreamSourceChannel,
    buffer: ByteBuffer,
  ): ZIO[Any, Option[Throwable], Chunk[Byte]] = for {
    bytesRead <- ZIO.effect {
      buffer.clear()
      channel.read(buffer)
    }.mapError(Some(_))
    result <- {
      if (bytesRead == -1) {
        ZIO.fail(None)
      } else if (bytesRead == 0) {
        ZIO.succeed(Chunk.empty)
      } else {
        ZIO.effectTotal {
          buffer.flip()
          val data = Array.ofDim[Byte](buffer.remaining())
          buffer.get(data)
          Chunk.fromArray(data)
        }
      }
    }
  } yield result

  private def runZIO(runtime: Runtime[Any])(zio: ZIO[Any, Nothing, Unit]): Unit = {
    runtime.unsafeRunAsync(zio) {
      case Exit.Success(_: Unit) => ()
      case Exit.Failure(_: Cause[Nothing]) => () // impossible
    }
  }
}
