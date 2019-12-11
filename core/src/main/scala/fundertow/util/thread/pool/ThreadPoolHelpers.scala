package fundertow.util.thread.pool

import java.util.concurrent.{ExecutorService, TimeUnit}

import scala.concurrent.duration.Duration

object ThreadPoolHelpers {

  /**
    * Returns true if the pool was shutdown, false if the timeout elapsed.
    *
    * Note that the timeout is used twice so this method can possibly wait for timeout * 2.
    *
    * @see https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html
    */
  def shutdownAndAwaitTermination(pool: ExecutorService, timeout: Duration): Boolean = {
    pool.shutdown() // Disable new tasks from being submitted

    // Wait a while for existing tasks to terminate
    if (!pool.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS)) {
      pool.shutdownNow() // Cancel currently executing tasks

      // Wait a while for tasks to respond to being cancelled
      pool.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS)
    } else {
      true
    }
  }
}
