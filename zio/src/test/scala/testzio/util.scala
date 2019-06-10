package testzio

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

object util {

  def stacktraceToString(t: Throwable): String = {
    val charset = StandardCharsets.UTF_8
    val baos = new ByteArrayOutputStream
    try {
      val ps = new PrintStream(baos, true, charset.name())
      try {
        t.printStackTrace(ps)
      } finally {
        ps.close()
      }
      new String(baos.toByteArray, charset).trim
    } finally {
      baos.close()
    }
  }
}
