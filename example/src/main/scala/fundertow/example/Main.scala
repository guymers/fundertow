package fundertow.example

import java.io.IOException
import java.nio.charset.StandardCharsets

import fundertow.http.HttpHeaders
import fundertow.http.HttpStatus
import fundertow.http.server.Response
import fundertow.zio.App
import fundertow.zio.http.server.HttpHandlerFactory
import io.undertow.io.Receiver.ErrorCallback
import io.undertow.io.Receiver.FullStringCallback
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.server.handlers.HttpContinueAcceptingHandler
import io.undertow.util.Headers
import org.slf4j.LoggerFactory
import zio.Chunk
import zio.Task
import zio.ZEnv
import zio.ZIO
import zio.ZManaged
import zio.stream.ZSink
import zio.stream.ZStream

object Main extends App {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] = {
    logger.info("Starting server")
    super.run(args)
  }

  override def createHandler: ZManaged[ZEnv, Nothing, HttpHandler] = (for {
    postSingle <- HttpHandlerFactory.single { request =>
      request.body.map { bytes =>
        val s = new String(bytes, StandardCharsets.UTF_8)

        Response[Task](HttpStatus.Ok, HttpHeaders.empty, ZIO.succeed(Array.emptyByteArray)).withBody(s)
      }
    }
    postStream <- HttpHandlerFactory.stream { request =>
      request.body
        .map(Chunk.fromArray)
        .run(ZSink.foldLeft[Chunk[Byte], Chunk[Byte]](Chunk.empty)(_ ++ _))
        .map { c =>
          val bytes = c.toArray
          val s = new String(bytes, StandardCharsets.UTF_8)

          Response[ZStream[Any, Throwable, ?]](HttpStatus.Ok, HttpHeaders.empty, ZStream.empty).withBody(s)
        }
    }
  } yield {
    val router = new RoutingHandler()
    router.get("/ping", pong)
    router.post("/post/single", postSingle)
    router.post("/post/stream", postStream)
    router.post("/post", new HttpHandler {
      override def handleRequest(exchange: HttpServerExchange): Unit = {
        exchange.getRequestReceiver.receiveFullString(
          new FullStringCallback {
            override def handle(exchange: HttpServerExchange, message: String): Unit = {
              exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, "text/plain")
              exchange.getResponseSender.send(message)
            }
          },
          new ErrorCallback {
            override def error(exchange: HttpServerExchange, e: IOException): Unit = {
              exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, "text/plain")
              exchange.getResponseSender.send("FAILURE")
            }
          }
        )
      }
    })
    router.setFallbackHandler(new HttpHandler {
      override def handleRequest(exchange: HttpServerExchange): Unit = {
        exchange.setStatusCode(404)
        exchange.getResponseSender.send("setFallbackHandler")
      }
    })
    router.setInvalidMethodHandler(new HttpHandler {
      override def handleRequest(exchange: HttpServerExchange): Unit = {
        exchange.setStatusCode(405)
        exchange.getResponseSender.send("setInvalidMethodHandler")
      }
    })

    val handlerChain: List[HttpHandler => HttpHandler] = List(
      new HttpContinueAcceptingHandler(_)
    )
    handlerChain.foldRight(router: HttpHandler) { case (f, handler) =>
      f(handler)
    }
  }).toManaged_

  private val pong: HttpHandler = new HttpHandler {
    override def handleRequest(exchange: HttpServerExchange): Unit = {
      logger.info("pong handler")
      exchange.setStatusCode(200)
      exchange.getResponseHeaders.add(Headers.CONTENT_TYPE, "text/plain; charset=UTF-8")
      exchange.getResponseSender.send("pong")
    }
  }

}
