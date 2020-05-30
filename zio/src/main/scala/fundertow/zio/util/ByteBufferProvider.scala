package fundertow.zio.util

import java.nio.ByteBuffer

import io.undertow.connector.ByteBufferPool
import io.undertow.connector.PooledByteBuffer
import org.xnio.IoUtils
import zio.ZIO
import zio.ZLayer
import zio.ZManaged

object ByteBufferProvider { self =>

  def allocate(byteBufferPool: ByteBufferPool): ZManaged[Any, Throwable, ByteBuffer] = {
    val acquire = ZIO.effect(byteBufferPool.allocate)

    def release(pooled: PooledByteBuffer): ZIO[Any, Nothing, Unit] = {
      ZIO.effectTotal {
        IoUtils.safeClose(pooled)
      }
    }

    ZManaged.make(acquire)(release).flatMap { pooled =>
      ZManaged.fromEffect {
        ZIO.effect(pooled.getBuffer)
      }
    }
  }

  object Service {
    def allocate: ZManaged[ByteBufferProvider, Throwable, ByteBuffer] = {
      ZManaged.accessManaged[ByteBufferProvider](_.get.allocate)
    }
  }
  trait Service {
    def allocate: ZManaged[Any, Throwable, ByteBuffer]
  }

  def layer(byteBufferPool: ByteBufferPool): ZLayer[Any, Nothing, ByteBufferProvider] = {
    ZLayer.succeed(service(byteBufferPool))
  }

  def service(byteBufferPool: ByteBufferPool): Service = new Service {
    override def allocate: ZManaged[Any, Throwable, ByteBuffer] = self.allocate(byteBufferPool)
  }
}
