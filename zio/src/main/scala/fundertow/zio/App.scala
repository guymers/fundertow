package fundertow.zio

import java.util.concurrent.RejectedExecutionException

import scala.concurrent.duration._

import fundertow.util.thread.pool.ThreadPoolHelpers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.GracefulShutdownHandler
import org.xnio.OptionMap
import org.xnio.Options
import org.xnio.Xnio
import org.xnio.XnioWorker
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.internal.ExecutionMetrics
import zio.internal.Executor
import zio.internal.Platform
import zio.internal.PlatformLive
import zio.random.Random
import zio.system.System
import zio.{App => ZIOApp}

trait App extends ZIOApp {
  import zio.console.putStrLn

  private val NumProcs = java.lang.Runtime.getRuntime.availableProcessors.max(2)
  val numIoThreads: Int = NumProcs * 2 // "Two IO threads per CPU core is a reasonable default."

  // only used for init while we create the Xnio worker
  override val Platform: Platform = PlatformLive.Global

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = {
    workerManaged.use { worker =>
      val runtime = createXNioRuntime(worker)
      serverManaged(worker, createHandler(runtime)).use { _ =>
        ZIO.never
      }
    }.catchAll { e =>
      onError("run error", e).as(1)
    }
  }

  private def workerManaged: ZManaged[Console with Blocking, Throwable, XnioWorker] = {
    val acquire: RIO[Console with Blocking, XnioWorker] = ZIO.effect {
      val workerOptions = workerOptionBuilder.getMap
      xnio.createWorker(workerOptions)
    }

    def release(worker: XnioWorker): ZIO[Console with Blocking, Nothing, Unit] = {
      val task = for {
        terminated <- zio.blocking.effectBlocking {
          ThreadPoolHelpers.shutdownAndAwaitTermination(worker, 5.seconds)
        }
        _ <- if (!terminated) {
          putStrLn("worker did not shutdown")
        } else {
          putStrLn("worker shutdown complete")
        }
      } yield ()
      task.catchAll { e =>
        onError("worker shutdown failed", e)
      }
    }

    ZManaged.make(acquire)(release)
  }

  private def serverManaged(
    worker: XnioWorker,
    handler: HttpHandler
  ): ZManaged[Console with Blocking, Throwable, Undertow] = {
    val acquire: RIO[Console with Blocking, (GracefulShutdownHandler, Undertow)] = ZIO.effect {
      val shutdownHandler = new GracefulShutdownHandler(handler)

      val server = undertowBuilder
        .setWorker(worker)
        .setHandler(shutdownHandler)
        .build
      server.start()

      (shutdownHandler, server)
    }

    def release(tuple: (GracefulShutdownHandler, Undertow)) = {
      val (shutdownHandler, server) = tuple

      val task = for {
        _ <- putStrLn("Server shutdown starting")
        shutdown <- zio.blocking.effectBlocking {
          shutdownHandler.shutdown()
          shutdownHandler.awaitShutdown(60.seconds.toMillis)
        }
        _ <- if (!shutdown) {
          putStrLn("All requests did not complete after waiting 60 seconds")
        } else UIO.unit
        _ <- zio.blocking.effectBlocking {
          server.stop()
        }
        _ <- putStrLn("Server shutdown complete")
      } yield ()
      task.catchAll { e =>
        onError("Server shutdown failed", e)
      }
    }

    ZManaged.make(acquire)(release).map(_._2)
  }

  protected def undertowBuilder: Undertow.Builder = {
    Undertow.builder
      .addHttpListener(8080, "localhost")
      .setServerOption(UndertowOptions.IDLE_TIMEOUT, java.lang.Integer.valueOf(60.seconds.toMillis.toInt))
      .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, java.lang.Long.valueOf(1 * 1024 * 1024))
  }

  private def createXNioRuntime(worker: XnioWorker): Runtime[Environment] = {
    val executor = createScalazExecutor(worker)
    val platform = PlatformLive.fromExecutor(executor)
      .withReportFailure(cause => if (!cause.interrupted) println(cause.toString)) // TODO
    val environment: Environment = new Clock.Live with Console.Live with System.Live with Random.Live with Blocking.Live
    Runtime(environment, platform)
  }

  private def createScalazExecutor(worker: XnioWorker) = new Executor {
    def metrics: Option[ExecutionMetrics] = None

    def yieldOpCount = 1024

    def submit(runnable: Runnable): Boolean =
      try {
        worker.execute(runnable)
        true
      } catch {
        case _: RejectedExecutionException => false
      }

    def here = false
  }

  protected val xnio: Xnio = Xnio.getInstance

  // defaults from Undertow.start
  protected def workerOptionBuilder: OptionMap.Builder = OptionMap.builder
    .set(Options.WORKER_IO_THREADS, numIoThreads)
    .set(Options.THREAD_DAEMON, false)
    .set(Options.CONNECTION_HIGH_WATER, 1000000)
    .set(Options.CONNECTION_LOW_WATER, 1000000)
    .set(Options.WORKER_TASK_MAX_THREADS, numIoThreads * 8)
    .set(Options.TCP_NODELAY, true)
    .set(Options.CORK, true)

  def createHandler(runtime: Runtime[Environment]): HttpHandler

  protected def onError(msg: String, e: Throwable): UIO[Unit] = ZIO.effectTotal {
    println(msg) // FIXME
    e.printStackTrace()
  }

}
