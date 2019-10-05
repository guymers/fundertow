package fundertow.zio.channels

import java.nio.ByteBuffer

import io.undertow.connector.ByteBufferPool
import io.undertow.connector.PooledByteBuffer
import org.xnio.ChannelListener
import org.xnio.IoUtils
import org.xnio.channels.StreamSourceChannel
import zio.Exit
import zio.Promise
import zio.UIO
import zio.ZIO
import zio.ZManaged
import zio.stream.ZStream
import zio.stream.ZStream.Pull

object StreamSourceChannelHelper {

  // TODO if (contentLength > maxBufferSize) error.error(exchange, new RequestToLargeException())

  def stream[R](
    byteBufferPool: ByteBufferPool,
    channel: ZManaged[R, Throwable, StreamSourceChannel],
    capacity: Int
  ): ZStream[R, Throwable, Array[Byte]] = {
    ZStream.managed(channel)
      .mapM { channel => setup(byteBufferPool, channel, capacity) }
      .flatMap { case (completion, queue) =>
        new ZStream(process(completion, queue))
      }
  }

  // from zio.interop.reactiveStreams.Adapters.process
  private def process[R, A](
    completion: Promise[Throwable, Unit],
    q: PauseAndResumeQueue[Throwable, A]
  ): ZManaged[R, Throwable, Pull[Any, Throwable, A]] = for {
    _ <- ZManaged.finalizer(q.shutdown)
    _ <- completion.await.ensuring(q.size.flatMap(n => if (n <= 0) q.shutdown else UIO.unit)).fork.toManaged_
  } yield {
    val take = q.take.flatMap(Pull.emit)

    q.size.flatMap { n =>
      if (n <= 0) completion.isDone.flatMap {
        case true  => completion.await.foldM(Pull.fail, _ => Pull.end)
        case false => take
      } else take
    }.orElse(
      completion.poll.flatMap {
        case None     => Pull.end
        case Some(io) => io.foldM(Pull.fail, _ => Pull.end)
      }
    )
  }

  private def setup(byteBufferPool: ByteBufferPool, channel: StreamSourceChannel, capacity: Int) = for {
    completion <- Promise.make[Throwable, Unit]
    queue <- {
      val pause = ZIO.effect(channel.suspendReads())
      val resume = ZIO.effect(channel.resumeReads())
      PauseAndResumeQueue[Throwable, Array[Byte]](capacity)(pause, resume)
    }

    runtime <- ZIO.runtime[Any]
    _ <- ZIO.effect {
      // TODO channel.getCloseSetter ?
      channel.getCloseSetter.set(new ChannelListener[StreamSourceChannel] {
        override def handleEvent(channel: StreamSourceChannel): Unit = {
          ()
        }
      })
    }
    _ <- ZIO.effect {
      // TODO channel.getCloseSetter ?
      // same logic as io.undertow.io.AsyncReceiverImpl:518
      channel.getReadSetter.set(new ChannelListener[StreamSourceChannel] {
        override def handleEvent(channel: StreamSourceChannel): Unit = {
          val zio = for {
            done <- completion.isDone
            paused <- queue.isPaused
            _ <- if (done || paused) ZIO.unit else {
              read(byteBufferPool, channel, completion, queue).flatMap { res =>
                if (res == 0) queue.resume else ZIO.unit
              }
            }
          } yield ()
          runtime.unsafeRunAsync(zio) {
            case Exit.Success(_) => ()
            case Exit.Failure(cause) if cause.interrupted => ()
            case Exit.Failure(cause) => val _ = runtime.unsafeRun(completion.fail(cause.squash))
          }
        }
      })
    }
    _ <- read(byteBufferPool, channel, completion, queue)
  } yield {
    (completion, queue)
  }

  private def read(
    byteBufferPool: ByteBufferPool,
    channel: StreamSourceChannel,
    completion: Promise[Throwable, Unit],
    q: PauseAndResumeQueue[Throwable, Array[Byte]]
  ): ZIO[Any, Throwable, Int] = {

    getPooledBuffer(byteBufferPool).use { buffer =>
      // same logic as io.undertow.io.AsyncReceiverImpl:531
      def go: ZIO[Any, Throwable, Int] = for {
        res <- ZIO.effect {
          buffer.clear()
          channel.read(buffer)
        }
        bytesRead <- {
          if (res == -1) {
            completion.succeed(()).as(res)
          } else if (res == 0) {
            ZIO.succeed(res)
          } else for {
            data <- ZIO.effect {
              buffer.flip()
              val data = Array.ofDim[Byte](buffer.remaining())
              buffer.get(data)
              data
            }
            _ <- q.offer(data)
            paused <- q.isPaused
            bytesRead <- if (paused) ZIO.succeed(res) else q.resume *> go
          } yield bytesRead
        }
      } yield bytesRead
      go
    }
  }

  private def getPooledBuffer(byteBufferPool: ByteBufferPool): ZManaged[Any, Throwable, ByteBuffer] = {
    val acquire = ZIO.effect(byteBufferPool.allocate)

    def release(pooled: PooledByteBuffer): UIO[Unit] = {
      ZIO.effectTotal {
        IoUtils.safeClose(pooled)
      }
    }

    ZManaged.make(acquire)(release).flatMap { pooled =>
      ZManaged.fromEffect {
        ZIO.effect(pooled.getBuffer)
      }
    }
  }
}
