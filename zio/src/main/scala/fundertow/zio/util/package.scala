package fundertow.zio

import zio.Has

package object util {

  type ByteBufferProvider = Has[ByteBufferProvider.Service]
}
