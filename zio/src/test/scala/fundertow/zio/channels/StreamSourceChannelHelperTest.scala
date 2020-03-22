package fundertow.zio.channels

import java.nio.charset.StandardCharsets

import io.undertow.connector.ByteBufferPool
import io.undertow.server.DefaultByteBufferPool
import zio.Has
import zio.ZIO
import zio.ZLayer
import zio.ZManaged
import zio.duration._
import zio.test._

object StreamSourceChannelHelperTest extends DefaultRunnableSpec {

  override val aspects = List(
    TestAspect.timeout(5.seconds)
  )

  private val capacity = 16

  private val withByteBufferPool: ZManaged[Any, Nothing, ByteBufferPool] = {
    val acquire = ZIO.effectTotal {
      new DefaultByteBufferPool(false, 1024)
    }
    def release(byteBufferPool: ByteBufferPool) = ZIO.effectTotal {
      byteBufferPool.close()
    }
    ZManaged.make(acquire)(release)
  }

  override def spec = suite("StreamSourceChannelHelperTest")(
    testM("initial data smaller than queue size") {
      ZIO.access[Has[ByteBufferPool]](_.get).flatMap { byteBufferPool =>
        val in = List.fill(2)("before")

        assertChannel(byteBufferPool, in)
      }
    },

    testM("initial data larger than queue size") {
      ZIO.access[Has[ByteBufferPool]](_.get).flatMap { byteBufferPool =>
        val in = List.fill(capacity * 2)("before")

        assertChannel(byteBufferPool, in)
      }
    },

    testM("initial data available and then more available later") {
      ZIO.access[Has[ByteBufferPool]](_.get).flatMap { byteBufferPool =>
        val in = List.fill(4)("before") ::: List("") ::: List.fill(4)("after")

        assertChannel(byteBufferPool, in, callReadSetterAfter = 250.milliseconds)
      }
    }
  ).provideCustomLayerShared(ZLayer.fromManaged(withByteBufferPool))


  private def assertChannel(
    byteBufferPool: ByteBufferPool,
    in: List[String],
    callReadSetterAfter: Duration = Duration.Zero
  ) = {
    val channel = ZManaged.effectTotal {
      val channel = new StreamSourceChannelStub(in.map(_.getBytes(StandardCharsets.UTF_8)))
      if (!callReadSetterAfter.isZero) channel.callReadSetter(callReadSetterAfter.asScala) else ()
      channel
    }

    StreamSourceChannelHelper.stream(byteBufferPool, channel, capacity)
      .runCollect
      .map { result =>
        val out = result.map(new String(_, StandardCharsets.UTF_8))
        assert(in.filter(_.nonEmpty))(Assertion.equalTo(out))
      }
  }
}
