package fundertow.zio.http.server

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

import scala.collection.JavaConverters._

import fundertow.Id
import fundertow.http.HttpHeaders
import fundertow.http.HttpMethod
import fundertow.http.HttpStatus
import fundertow.http.HttpVersion
import fundertow.http.server.Request
import fundertow.http.server.Response
import fundertow.zio.channels.StreamSourceChannelHelper
import fundertow.zio.util.ByteBufferProvider
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
    f: Request[Task, Array] => ZIO[R, E, Response[Task, Array]]
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
        Left(ZIO.effectTotal(exchange.endExchange())) // TODO does this cancel receiveFullBytes?
      }
      val request = createRequest(exchange, body)
      f(request).flatMap { response =>
        sendResponse(exchange, response)
      }
    }
  }

  def stream[R, E](
    f: Request[ZStream[Any, Throwable, ?], Id] => ZIO[R, E, Response[ZStream[Any, Throwable, ?], Id]]
  ): ZIO[R, Nothing, HttpHandler] = {
    createHttpHandler { exchange =>
      val channel = ZIO.effect(exchange.getRequestChannel).toManaged { channel =>
        ZIO.effectTotal {
          IoUtils.safeShutdownReads(channel)
        }
      }

      val layer = ByteBufferProvider.layer(exchange.getConnection.getByteBufferPool)
      val body = ZStream.managed(channel).flatMap(StreamSourceChannelHelper.stream).provideLayer(layer)
      val request = createRequest[ZStream[Any, Throwable, ?], Id](exchange, body)
      f(request).flatMap { response =>
        streamResponse(exchange, response)
      }
    }
  }

  private def createRequest[F[_], C[_]](exchange: HttpServerExchange, body: F[C[Byte]]) = {
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
                case Exit.Success(_) => ()
                case Exit.Failure(Cause.empty) => ()
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
    response: Response[ZIO[R, E, ?], Array]
  ): ZIO[R, E, Unit] = for {
    _ <- setResponseHeaders(exchange, response)
    result <- withSender(exchange).use { sender =>
      for {
        data <- response.body
        result <- wrapIoCallback(sender.send(ByteBuffer.wrap(data), _))
      } yield result
    }
  } yield result

  private def streamResponse[R, E >: IOException](
    exchange: HttpServerExchange,
    response: Response[ZStream[R, E, ?], Id]
  ): ZIO[R, E, Unit] = for {
    _ <- setResponseHeaders(exchange, response)
    result <- withSender(exchange).use { sender =>
      // FIXME hard coded randomly chosen value
      response.body.chunkN(1024).foreachChunk { chunk =>
        wrapIoCallback(sender.send(ByteBuffer.wrap(chunk.toArray), _))
      }
    }
  } yield result

  private def withSender(exchange: HttpServerExchange) = {
    ZIO.effectTotal {
      exchange.getResponseSender
    }.toManaged { sender =>
      wrapIoCallback(sender.close(_)).catchAll { e =>
        ZIO.effectTotal {
          IoUtils.safeClose(new Closeable {
            override def close(): Unit = throw e
          })
        }
      }
    }
  }

  private def setResponseHeaders[R, E, F[_, _, _], C[_]](
    exchange: HttpServerExchange,
    response: Response[F[R, E, ?], C]
  ): ZIO[R, E, Unit] = ZIO.effectTotal {
    exchange.setStatusCode(response.status.code)

    response.headers.iterator.foreach { value =>
      val values = value.iterator().asScala.toList.asJavaCollection
      exchange.getResponseHeaders.putAll(value.getHeaderName, values)
    }
  }

  private def wrapIoCallback(f: IoCallback => Unit): ZIO[Any, IOException, Unit] = {
    ZIO.effectAsync { cb: (ZIO[Any, IOException, Unit] => Unit) =>
      f(new IoCallback {
        override def onComplete(exchange: HttpServerExchange, s: Sender): Unit = cb(ZIO.unit)
        override def onException(exchange: HttpServerExchange, s: Sender, e: IOException): Unit = cb(ZIO.fail(e))
      })
    }
  }

}
