package fundertow.zio.channels

import java.nio.charset.StandardCharsets

import scala.concurrent.duration._

import io.undertow.connector.ByteBufferPool
import io.undertow.server.DefaultByteBufferPool
import testzio._
import zio.ZIO
import zio.ZManaged
import zio.duration.{Duration => ZDuration}

object StreamSourceChannelHelperTest {

  private val capacity = 16

  private val managedByteBufferPool = {
    val acquire = ZIO.effectTotal {
      new DefaultByteBufferPool(false, 1024)
    }
    def release(byteBufferPool: ByteBufferPool) = ZIO.effectTotal {
      byteBufferPool.close()
    }
    ZManaged.make(acquire)(release)
  }

  val Suite = managedSuite(managedByteBufferPool)("StreamSourceChannelHelperTest")(
    test("initial data smaller than queue size") { byteBufferPool =>
      val in = List.fill(2)("before")

      assertChannel(byteBufferPool, in)
    },

    test("initial data larger than queue size") { byteBufferPool =>
      val in = List.fill(capacity * 2)("before")

      assertChannel(byteBufferPool, in)
    },

    test("initial data available and then more available later") { byteBufferPool =>
      val in = List.fill(4)("before") ::: List("") ::: List.fill(4)("after")

      assertChannel(byteBufferPool, in, callReadSetterAfter = 250.milliseconds)
    }
  )

  private def assertChannel(
    byteBufferPool: ByteBufferPool,
    in: List[String],
    callReadSetterAfter: Duration = Duration.Undefined
  ) = {
    val channel = ZManaged.effectTotal {
      val channel = new StreamSourceChannelStub(in.map(_.getBytes(StandardCharsets.UTF_8)))
      if (callReadSetterAfter.isFinite) channel.callReadSetter(callReadSetterAfter) else ()
      channel
    }

    StreamSourceChannelHelper.stream(byteBufferPool, channel, capacity)
      .runCollect
      .timeout(ZDuration.fromScala(3.seconds))
      .map {
        case None => fail("timed out after 3 seconds")
        case Some(result) =>
          val out = result.map(new String(_, StandardCharsets.UTF_8))
          assert(in.filter(_.nonEmpty) == out)
      }
  }
}
