package fundertow.zio.channels

import io.undertow.connector.ByteBufferPool
import io.undertow.connector.PooledByteBuffer
import org.xnio.ChannelListener
import org.xnio.IoUtils
import org.xnio.channels.StreamSourceChannel
import scalaz.zio.Exit
import scalaz.zio.Promise
import scalaz.zio.Queue
import scalaz.zio.Ref
import scalaz.zio.Semaphore
import scalaz.zio.Task
import scalaz.zio.UIO
import scalaz.zio.ZIO
import scalaz.zio.ZQueue
import scalaz.zio.stream.ZStream

object StreamSourceChannelHelper {

  // if (contentLength > maxBufferSize) error.error(exchange, new RequestToLargeException())

  def stream(
    byteBufferPool: ByteBufferPool,
    channel: ZIO[Any, Throwable, StreamSourceChannel],
    capacity: Int
  ): ZStream[Any, Throwable, Array[Byte]] = {

    sourceChannel(channel)
      .mapM { channel => setup(byteBufferPool, channel, capacity) }
      .flatMap { case (completion, queue, pause, resume) =>
        createStream(completion, queue, pause, resume)
      }
  }

  private def createStream[A](
    completion: Promise[Throwable, Unit],
    q: Queue[A],
    pause: Task[Unit],
    resume: Task[Unit]
  ): ZStream[Any, Throwable, A] = {
    // based on scalaz.zio.interop.reactiveStreams.QueueSubscriber

    /*
     * Unfold q. When `onComplete` or `onError` is signalled, take the remaining values from `q`, then shut down.
     * `onComplete` or `onError` always are the last signal if they occur. We optimistically take from `q` and rely on
     * interruption in case that they are signalled while we wait. `forkQShutdownHook` ensures that `take` is
     * interrupted in case `q` is empty while `onComplete` or `onError` is signalled.
     * When we see `completion.done` after `loop`, the `Publisher` has signalled `onComplete` or `onError` and we are
     * done. Otherwise the stream has completed before and we need to cancel the subscription.
     */
    new ZStream[Any, Throwable, A] {
      private val high: Int = q.capacity * 95 / 100
      private val low: Int = high / 2

      private def forkQShutdownHook = {
        completion.await.ensuring(q.size.flatMap(n => if (n <= 0) q.shutdown else UIO.unit)).fork
      }

      // type Fold[R, E, +A, S] = ZIO[R, Nothing, (S, S => Boolean, (S, A) => ZIO[R, E, S]) => ZIO[R, E, S]]

      override def fold[R1 <: Any, E1 >: Throwable, A1 >: A, S]: ZStream.Fold[R1, E1, A1, S] = {
        forkQShutdownHook.map { _ =>
          (s: S, cont: S => Boolean, f: (S, A1) => ZIO[R1, E1, S]) =>
            def loop(s: S): ZIO[R1, E1, S] = {
              if (!cont(s)) UIO.succeed(s)
              else {
                def takeAndLoop = q.take.flatMap(f(s, _)).flatMap(loop)
                def completeWithS = completion.await.const(s)
                q.size.flatMap { n =>
                  if (n <= 0) completion.isDone.flatMap {
                    case true => completeWithS
                    case false => takeAndLoop
                  } else {
                    val action = {
                      if (n < low) resume
                      else if (n > high) pause
                      else UIO.unit
                    }
                    action *> takeAndLoop
                  }
                } orElse completeWithS
              }
            }
            loop(s).ensuring(q.shutdown)
        }.onInterrupt(q.shutdown)
      }
    }
  }

  private def sourceChannel(
    channel: ZIO[Any, Throwable, StreamSourceChannel]
  ): ZStream[Any, Throwable, StreamSourceChannel] = {
    val acquire = for {
      used <- Ref.make(false)
      c <- channel
    } yield (used, c)

    def release(tuple: (Ref[Boolean], StreamSourceChannel)): UIO[Unit] = {
      val (_, channel) = tuple
      ZIO.effectTotal {
        IoUtils.safeShutdownReads(channel)
      }
    }

    ZStream.bracket(acquire)(release) { case (usedR, receiver) =>
      for {
        used <- usedR.get
        result = if (used) None else Some(receiver)
        _ <- usedR.set(true)
      } yield result
    }
  }

  private def setup(byteBufferPool: ByteBufferPool, channel: StreamSourceChannel, capacity: Int) = for {
    completion <- Promise.make[Throwable, Unit]
    queue <- ZQueue.bounded[Array[Byte]](capacity)
    (isPaused, pause, resume) <- suspendResumeReads(channel)

    runtime <- ZIO.runtime[Any]
    _ <- ZIO.effectTotal {
      // same logic as io.undertow.io.AsyncReceiverImpl:518
      channel.getReadSetter.set(new ChannelListener[StreamSourceChannel] {
        override def handleEvent(channel: StreamSourceChannel): Unit = {
          val zio = for {
            done <- completion.isDone
            paused <- isPaused.get
            _ <- if (done || paused) ZIO.unit else {
              read(byteBufferPool, channel, completion, queue, isPaused).flatMap { res =>
                if (res == 0) resume else ZIO.unit
              }
            }
          } yield ()
          runtime.unsafeRunSync(zio) match {
            case Exit.Success(_) => ()
            case Exit.Failure(cause) if cause.interrupted => ()
            case Exit.Failure(cause) => val _ = runtime.unsafeRun(completion.fail(cause.squash))
          }
        }
      })
    }.ensuring(UIO.effectTotal(channel.getReadSetter.set(null)))
    _ <- read(byteBufferPool, channel, completion, queue, isPaused)
  } yield {
    (completion, queue, pause, resume)
  }

  private def suspendResumeReads(channel: StreamSourceChannel) = for {
    pausedR <- Ref.make(false)
    pausedS <- Semaphore.make(1)
    pause = pausedS.withPermit(for {
      paused <- pausedR.get
      _ <- if (!paused) ZIO.effect(channel.suspendReads()) *> pausedR.set(true) else UIO.unit
    } yield ())
    resume = pausedS.withPermit(for {
      paused <- pausedR.get
      _ <- if (paused) ZIO.effect(channel.resumeReads()) *> pausedR.set(false) else UIO.unit
    } yield ())
  } yield (pausedR, pause, resume)

  private def read(
    byteBufferPool: ByteBufferPool,
    channel: StreamSourceChannel,
    completion: Promise[Throwable, Unit],
    queue: Queue[Array[Byte]],
    isPaused: Ref[Boolean]
  ): ZIO[Any, Throwable, Int] = {
    val acquire = ZIO.effect(byteBufferPool.allocate)

    def release(pooled: PooledByteBuffer): UIO[Unit] = {
      ZIO.effectTotal {
        IoUtils.safeClose(pooled)
      }
    }

    ZIO.bracket(acquire)(release) { pooled =>
      ZIO.effect(pooled.getBuffer).flatMap { buffer =>
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
              _ <- queue.offer(data) // bounded queue will always return true
              paused <- isPaused.get
              bytesRead <- if (paused) ZIO.succeed(res) else ZIO.effect(channel.resumeReads()) *> go
            } yield bytesRead
          }
        } yield bytesRead
        go
      }
    }
  }
}
