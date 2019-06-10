package fundertow.zio.channels

import java.nio.ByteBuffer

import io.undertow.connector.ByteBufferPool
import io.undertow.connector.PooledByteBuffer
import org.xnio.ChannelListener
import org.xnio.IoUtils
import org.xnio.channels.StreamSourceChannel
import scalaz.zio.Exit
import scalaz.zio.Promise
import scalaz.zio.UIO
import scalaz.zio.ZIO
import scalaz.zio.ZManaged
import scalaz.zio.stream.ZStream

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
        createStream(completion, queue)
      }
  }

  private def createStream[A](
    completion: Promise[Throwable, Unit],
    q: PauseAndResumeQueue[Throwable, A]
  ): ZStream[Any, Throwable, A] = new ZStream[Any, Throwable, A] {
    // based on scalaz.zio.interop.reactiveStreams.QueueSubscriber

    private def forkQShutdownHook = {
      completion.await.ensuring(q.size.flatMap(n => if (n <= 0) q.shutdown else UIO.unit)).fork
    }

    override def fold[R1 <: Any, E1 >: Throwable, A1 >: A, S]: ZStream.Fold[R1, E1, A1, S] = {
      for {
        _ <- ZManaged.finalizer(q.shutdown)
        _ <- forkQShutdownHook.toManaged_
      } yield { (s: S, cont: S => Boolean, f: (S, A1) => ZIO[R1, E1, S]) =>
        def loop(s: S): ZIO[R1, E1, S] = {
          if (!cont(s)) UIO.succeed(s)
          else {
            def takeAndLoop = q.take.flatMap(f(s, _)).flatMap(loop)
            def completeWithS = completion.await.const(s)
            q.size.flatMap { n =>
              if (n <= 0) completion.isDone.flatMap {
                case true => completeWithS
                case false => takeAndLoop
              } else takeAndLoop
            } orElse completeWithS
          }
        }
        loop(s).ensuring(q.shutdown).toManaged_
      }
    }
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
            completion.succeed(()).const(res)
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
