package fundertow.zio.http.server

import java.io.IOException
import java.nio.ByteBuffer

import scala.collection.JavaConverters._

import fundertow.http.HttpHeaders
import fundertow.http.HttpMethod
import fundertow.http.HttpStatus
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
import zio._
import zio.stream.ZStream

object HttpHandlerFactory {

  def single[R, E](
    f: Request[Task] => ZIO[R, E, Response[Task]]
  ): ZIO[R, Nothing, HttpHandler] = {
    createHttpHandler { exchange =>
      val body = Task.effectAsyncInterrupt[Array[Byte]] { cb =>
        exchange.getRequestReceiver.receiveFullBytes(
          new FullBytesCallback {
            override def handle(exchange: HttpServerExchange, bytes: Array[Byte]): Unit = cb(ZIO.succeed(bytes))
          },
          new ErrorCallback {
            override def error(exchange: HttpServerExchange, e: IOException): Unit = cb(ZIO.fail(e))
          }
        )
        Left(ZIO.effectTotal(exchange.endExchange()))
      }
      val request = createRequest(exchange, body)
      f(request).flatMap { response =>
        sendResponse(exchange, response)
      }
    }
  }

  def stream[R, E](
    f: Request[ZStream[Any, Throwable, ?]] => ZIO[R, E, Response[ZStream[Any, Throwable, ?]]]
  ): ZIO[R, Nothing, HttpHandler] = {
    createHttpHandler { exchange =>
      val channel = ZIO.effect(exchange.getRequestChannel).toManaged { channel =>
        ZIO.effectTotal {
          IoUtils.safeShutdownReads(channel)
        }
      }

      val body = StreamSourceChannelHelper.stream(
        exchange.getConnection.getByteBufferPool,
        channel,
        capacity = 128 // FIXME hard coded
      )
      val request = createRequest(exchange, body)
      f(request).flatMap { response =>
        streamResponse(exchange, response)
      }
    }
  }

  private def createRequest[F[_]](exchange: HttpServerExchange, body: F[Array[Byte]]) = {
    Request(
      version = HttpVersion.fromString(exchange.getProtocol).get, // TODO
      isSecure = exchange.isSecure,
      uri = exchange.getRequestURI,
      method = HttpMethod.fromString(exchange.getRequestMethod),
      headers = HttpHeaders.apply(exchange.getRequestHeaders),
      body = body
    )
  }

  private def createHttpHandler[R, E](f: HttpServerExchange => ZIO[R, E, Unit]): ZIO[R, Nothing, HttpHandler] = {
    ZIO.runtime.map { runtime =>
      new HttpHandler {
        override def handleRequest(exchange: HttpServerExchange): Unit = {
          val t = f(exchange)
            .catchAllCause { cause =>
              if (cause.isEmpty) {
                ZIO.unit
              } else if (cause.interrupted) {
                ZIO.effectTotal {
                  println("interrupted") // FIXME
                  if (!exchange.isResponseStarted) {
                    exchange.setStatusCode(HttpStatus.ServiceUnavailable.code)
                  }
                }
              } else {
                ZIO.effectTotal {
                  println(s"error: ${cause.prettyPrint}") // FIXME
                  if (!exchange.isResponseStarted) {
                    exchange.setStatusCode(HttpStatus.InternalServerError.code)
                  }
                }
              }
            }
            .ensuring {
              ZIO.effectTotal {
                exchange.endExchange()
              }
            }

          // https://stackoverflow.com/a/25223070
          val _ = exchange.dispatch(SameThreadExecutor.INSTANCE, new Runnable {
            override def run(): Unit = {
              runtime.unsafeRunAsync(t) {
                case Exit.Success(()) => ()
                case Exit.Failure(Cause.Empty) => ()
                case Exit.Failure(cause) => runtime.platform.reportFailure(cause)
              }
            }
          })
        }
      }
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
