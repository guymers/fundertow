package fundertow.zio.channels

import zio._

object PauseAndResumeQueue {

  /**
    * Create a Queue that calls `pause` when it nears capacity
    * and `resume` when the queue falls back below half capacity.
    */
  def apply[E, A](capacity: Int)(
    pause: ZIO[Any, E, Unit],
    resume: ZIO[Any, E, Unit]
  ): ZIO[Any, Nothing, PauseAndResumeQueue[E, A]] = for {
    pausedR <- Ref.make(false)
    pausedS <- Semaphore.make(1)
    _ <- ZIO.effectTotal(require(capacity >= 8)) // FIXME
    q <- Queue.bounded[A](capacity)
  } yield {
    val safePause = pausedS.withPermit(for {
      paused <- pausedR.get
      _ <- if (!paused) pausedR.set(true) *> pause else UIO.unit
    } yield ())
    val safeResume = pausedS.withPermit(for {
      paused <- pausedR.get
      _ <- if (paused) pausedR.set(false) *> resume else UIO.unit
    } yield ())

    new PauseAndResumeQueue(q, pausedR, safePause, safeResume)
  }
}

final class PauseAndResumeQueue[+E, A] private (
  q: Queue[A],
  paused: Ref[Boolean],
  val pause: ZIO[Any, E, Unit],
  val resume: ZIO[Any, E, Unit]
) {
  private val high: Int = q.capacity - (q.capacity * 5 / 100).max(4)
  private val low: Int = high / 2

  val capacity: Int = q.capacity
  def size: UIO[Int] = q.size

  def isPaused: UIO[Boolean] = paused.get

  def offer(a: A): ZIO[Any, E, Unit] = {
    size.flatMap(n => if (n > high) pause else ZIO.unit) *>
      q.offer(a).const(()) // a bounded queue blocks when full, it never returns false
  }
  def take: ZIO[Any, E, A] = {
    size.flatMap(n => if (n < low) resume else ZIO.unit) *>
      q.take
  }

  def shutdown: UIO[Unit] = q.shutdown
  def awaitShutdown: UIO[Unit] = q.awaitShutdown
  def isShutdown: UIO[Boolean] = q.isShutdown
}
