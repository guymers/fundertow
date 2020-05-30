package fundertow.zio.channels

import java.nio.charset.StandardCharsets

import fundertow.zio.util.ByteBufferProvider
import io.undertow.connector.ByteBufferPool
import io.undertow.server.DefaultByteBufferPool
import org.xnio.IoUtils
import zio.Chunk
import zio.ZIO
import zio.ZLayer
import zio.ZManaged
import zio.duration._
import zio.stream.ZSink
import zio.test._

object StreamSourceChannelHelperTest extends DefaultRunnableSpec {

  override val aspects = List(
    TestAspect.timeout(5.seconds)
  )

  private val byteBufferPool = {
    val acquire = ZIO.effectTotal {
      new DefaultByteBufferPool(false, 1024)
    }
    def release(byteBufferPool: ByteBufferPool) = ZIO.effectTotal {
      IoUtils.safeClose(byteBufferPool)
    }
    val managed = ZManaged.make(acquire)(release)
    ZLayer.fromManaged(managed.map(ByteBufferProvider.service))
  }

  override val spec = suite("StreamSourceChannelHelperTest")(
    testM("only initial data") {
      val in = List.fill(10)("before")

      assertChannel(in)
    },

    testM("initial data available and then more available later") {
      val in = List.fill(4)("before") ::: List("") ::: List.fill(4)("after")

      assertChannel(in, callReadSetterAfter = 250.milliseconds)
    }
  ).provideCustomLayerShared(byteBufferPool)


  private def assertChannel(
    in: List[String],
    callReadSetterAfter: Duration = Duration.Zero
  ) = {
    val channel = {
      val channel = new StreamSourceChannelStub(in.map(_.getBytes(StandardCharsets.UTF_8)))
      if (!callReadSetterAfter.isZero) channel.callReadSetter(callReadSetterAfter.asScala) else ()
      channel
    }

    StreamSourceChannelHelper.stream(channel)
      .run(ZSink.foldLeftChunks[Byte, Chunk[Chunk[Byte]]](Chunk.empty)(_ :+ _))
      .map { result =>
        val out = result.map(_.toArray).map(new String(_, StandardCharsets.UTF_8))
        assert(in)(Assertion.equalTo(out.toList))
      }
  }
}
