package fundertow.zio.http.server

import java.io.IOException
import java.nio.ByteBuffer

import scala.collection.JavaConverters._

import fundertow.http.HttpHeaders
import fundertow.http.HttpMethod
import fundertow.http.HttpVersion
import fundertow.http.server.Request
import fundertow.http.server.Response
import fundertow.zio.channels.StreamSourceChannelHelper
import io.undertow.io.IoCallback
import io.undertow.io.Receiver.ErrorCallback
import io.undertow.io.Receiver.FullBytesCallback
import io.undertow.io.Sender
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.SameThreadExecutor
import org.xnio.IoUtils
import org.xnio.channels.StreamSourceChannel
import zio._
import zio.stream.ZStream

object HttpHandlerFactory {

  // FIXME a better way to do the 4 combinations

  def single[R](runtime: Runtime[R])(
    f: Request[Task] => ZIO[R, Throwable, Response[Task]]
  ): HttpHandler = createHttpHandler(runtime) { exchange =>
    val body: Task[Array[Byte]] = Task.effectAsync { cb: (ZIO[Any, IOException, Array[Byte]] => Unit) =>
      exchange.getRequestReceiver.receiveFullBytes(
        new FullBytesCallback {
          override def handle(exchange: HttpServerExchange, bytes: Array[Byte]): Unit = cb(ZIO.succeed(bytes))
        },
        new ErrorCallback {
          override def error(exchange: HttpServerExchange, e: IOException): Unit = cb(ZIO.fail(e))
        }
      )
    }
    val request = Request(
      version = HttpVersion.fromString(exchange.getProtocol).get, // TODO
      isSecure = exchange.isSecure,
      uri = exchange.getRequestURI,
      method = HttpMethod.fromString(exchange.getRequestMethod),
      headers = HttpHeaders.apply(exchange.getRequestHeaders),
      body = body
    )
    f(request).flatMap { response =>
      sendResponse(exchange, response)
    }
  }

  def stream[R](runtime: Runtime[R])(
    f: Request[ZStream[Any, Throwable, ?]] => ZIO[R, Throwable, Response[ZStream[Any, Throwable, ?]]]
  ): HttpHandler = createHttpHandler(runtime) { exchange =>
    val channel = {
      val acquire = ZIO.effect(exchange.getRequestChannel)
      def release(channel: StreamSourceChannel): UIO[Unit] = {
        ZIO.effectTotal {
          IoUtils.safeShutdownReads(channel)
        }
      }
      ZManaged.make(acquire)(release)
    }

    val body = StreamSourceChannelHelper.stream(
      exchange.getConnection.getByteBufferPool,
      channel,
      capacity = 128 // FIXME hard coded
    )
    val request = Request(
      version = HttpVersion.fromString(exchange.getProtocol).get, // TODO
      isSecure = exchange.isSecure,
      uri = exchange.getRequestURI,
      method = HttpMethod.fromString(exchange.getRequestMethod),
      headers = HttpHeaders.apply(exchange.getRequestHeaders),
      body = body
    )
    f(request).flatMap { response =>
      streamResponse(exchange, response)
    }
  }

  private def createHttpHandler[R](runtime: Runtime[R])(
    performResponse: HttpServerExchange => RIO[R, Unit]
  ): HttpHandler = new HttpHandler {
    override def handleRequest(exchange: HttpServerExchange): Unit = {
      val t = performResponse(exchange).onError { cause =>
        if (cause.succeeded) {
          ZIO.unit
        } else if (cause.interrupted) {
          ZIO.effectTotal {
            println("interrupted") // FIXME
            if (!exchange.isResponseStarted) {
              exchange.setStatusCode(500)
              exchange.getResponseSender.send("interrupted")
            }
          }
        } else {
          ZIO.effectTotal {
            val e = cause.squash
            println(s"error: ${e.getMessage}") // FIXME
            if (!exchange.isResponseStarted) {
              exchange.setStatusCode(500)
              exchange.getResponseSender.send(s"error: ${e.getMessage}")
            }
          }

        }
      }.ensuring {
        ZIO.effectTotal {
          exchange.endExchange()
        }
      }

      // https://stackoverflow.com/a/25223070
      val _ = exchange.dispatch(SameThreadExecutor.INSTANCE, new Runnable {
        override def run(): Unit = {
          runtime.unsafeRunAsync(t) {
            case Exit.Success(()) => ()
            case Exit.Failure(cause) =>
              println(cause) // FIXME we handled the errors in onError
          }
        }
      })
    }
  }

  private def sendResponse[R, E >: IOException](
    exchange: HttpServerExchange,
    response: Response[ZIO[R, E, ?]]
  ): ZIO[R, E, Unit] = for {
    _ <- setResponseHeaders(exchange, response)
    sender <- ZIO.effectTotal {
      exchange.getResponseSender
    }
    result <- response.body.flatMap { data =>
      performIoCallback(sender.send(ByteBuffer.wrap(data), _))
    }
    _ <- performIoCallback(sender.close(_))
  } yield result

  private def streamResponse[R, E >: IOException](
    exchange: HttpServerExchange,
    response: Response[ZStream[R, E, ?]]
  ): ZIO[R, E, Unit] = for {
    _ <- setResponseHeaders(exchange, response)
    sender <- ZIO.effectTotal {
      exchange.getResponseSender
    }
    result <- {
      val s = response.body.mapM { data =>
        performIoCallback(sender.send(ByteBuffer.wrap(data), _))
      } ++ ZStream.fromEffect {
        performIoCallback(sender.close(_))
      }
      s.runDrain
    }
  } yield result

  private def setResponseHeaders[R, E, F[_, _, _]](
    exchange: HttpServerExchange,
    response: Response[F[R, E, ?]]
  ): ZIO[R, E, Unit] = ZIO.effectTotal {
    exchange.setStatusCode(response.status.code)

    response.headers.iterator.foreach { value =>
      exchange.getResponseHeaders.putAll(value.getHeaderName, value.iterator().asScala.toList.asJavaCollection)
    }
  }

  private def performIoCallback(f: IoCallback => Unit): ZIO[Any, IOException, Unit] = {
    ZIO.effectAsync { cb: (ZIO[Any, IOException, Unit] => Unit) =>
      f(new IoCallback {
        override def onComplete(exchange: HttpServerExchange, s: Sender): Unit = cb(ZIO.unit)
        override def onException(exchange: HttpServerExchange, s: Sender, e: IOException): Unit = cb(ZIO.fail(e))
      })
    }
  }

}
