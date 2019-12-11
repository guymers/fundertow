package fundertow.zio

import java.util.concurrent.TimeUnit

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
import zio.console.Console
import zio.duration._
import zio.internal.Platform
import zio.{App => ZIOApp}

trait App extends ZIOApp {
  import zio.console.putStrLn

  private val NumProcs = java.lang.Runtime.getRuntime.availableProcessors.max(2)
  val numIoThreads: Int = NumProcs * 2 // "Two IO threads per CPU core is a reasonable default."

  protected val xnio: Xnio = Xnio.getInstance
  private val worker = xnio.createWorker(workerOptionBuilder.getMap)

  // only used for init while we create the Xnio worker
  override val platform: Platform = XnioPlatform.create(worker)

  def createHandler: ZManaged[ZEnv, Nothing, HttpHandler]

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] = {
    (for {
      handler <- createHandler
      server <- createServer(worker, handler)
    } yield server).useForever.orDie
  }

  private def createServer(
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
          shutdownHandler.awaitShutdown(60.seconds.toMillis) // FIXME configurable
        }
        _ <- putStrLn("All requests did not complete after waiting 60 seconds").when(!shutdown)
        _ <- zio.blocking.effectBlocking {
          server.stop()
        }
        _ <- putStrLn("Server shutdown complete")
      } yield ()
      task.orDieWith(t => ServerShutdownFailed(t))
    }

    ZManaged.make(acquire)(release).map(_._2)
  }

  protected def undertowBuilder: Undertow.Builder = {
    Undertow.builder
      .addHttpListener(8080, "localhost")
      .setServerOption(UndertowOptions.IDLE_TIMEOUT, java.lang.Integer.valueOf(60.seconds.toMillis.toInt))
      .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, java.lang.Long.valueOf(1 * 1024 * 1024))
  }

  // defaults from Undertow.start
  protected def workerOptionBuilder: OptionMap.Builder = OptionMap.builder
    .set(Options.WORKER_IO_THREADS, numIoThreads)
    .set(Options.THREAD_DAEMON, true)
    .set(Options.CONNECTION_HIGH_WATER, 1000000)
    .set(Options.CONNECTION_LOW_WATER, 1000000)
    .set(Options.WORKER_TASK_MAX_THREADS, numIoThreads * 8)
    .set(Options.TCP_NODELAY, true)
    .set(Options.CORK, true)

}
