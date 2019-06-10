package fundertow.zio.channels

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.Duration

import org.xnio
import org.xnio.ChannelListener
import org.xnio.XnioExecutor
import org.xnio.XnioIoThread
import org.xnio.XnioWorker
import org.xnio.channels.StreamSinkChannel
import org.xnio.channels.StreamSourceChannel

class StreamSourceChannelStub(data: List[Array[Byte]]) extends StreamSourceChannel {
  private val readResumed = new AtomicBoolean(false)
  val readsShutDown = new AtomicBoolean(false)
  val closed = new AtomicBoolean(false)

  val suspendReadsCalled = new AtomicInteger(0)
  val resumeReadsCalled = new AtomicInteger(0)

  var remaining: List[Array[Byte]] = data

  private var readListener: ChannelListener[_ >: StreamSourceChannel] = _
  private var closeListener: ChannelListener[_ >: StreamSourceChannel] = _

  override def transferTo(position: Long, count: Long, target: FileChannel): Long = ???
  override def transferTo(count: Long, throughBuffer: ByteBuffer, target: StreamSinkChannel): Long = ???

  def callReadSetter(delay: Duration): Unit = {
    val channel = this
    val r = new Runnable() {
      override def run(): Unit = {
        Thread.sleep(delay.toMillis)
        readListener.handleEvent(channel)
      }
    }
    new Thread(r).start()
  }

  def callCloseSetter(): Unit = {
    closeListener.handleEvent(this)
  }

  override val getReadSetter: ChannelListener.Setter[_ <: StreamSourceChannel] = {
    (listener: ChannelListener[_ >: StreamSourceChannel]) => {
      readListener = listener
    }
  }
  override val getCloseSetter: ChannelListener.Setter[_ <: StreamSourceChannel] = {
    (listener: ChannelListener[_ >: StreamSourceChannel]) => {
      closeListener = listener
    }
  }

  override def read(dsts: Array[ByteBuffer], offset: Int, length: Int): Long = ???
  override def read(dsts: Array[ByteBuffer]): Long = ???
  override def read(dst: ByteBuffer): Int = {
    if (!isOpen) -1
    else {
      remaining match {
        case Nil => -1
        case head :: tail =>
          val (data, overflow) = head.splitAt(dst.capacity())
          dst.clear()
          dst.put(data)
          if (overflow.nonEmpty) {
            remaining = overflow :: tail
          } else {
            remaining = tail
          }
          data.length
      }
    }
  }

  override def suspendReads(): Unit = {
    suspendReadsCalled.getAndAdd(1)
    readResumed.set(false)
  }
  override def resumeReads(): Unit = {
    resumeReadsCalled.getAndAdd(1)
    readResumed.set(true)
    if (readListener != null) {
      readListener.handleEvent(this)
    }
  }
  override def isReadResumed: Boolean = readResumed.get()

  override def wakeupReads(): Unit = ???
  override def shutdownReads(): Unit = readsShutDown.set(true)

  override def awaitReadable(): Unit = ???
  override def awaitReadable(time: Long, timeUnit: TimeUnit): Unit = ???

  override def supportsOption(option: xnio.Option[_]): Boolean = ???
  override def getOption[T](option: xnio.Option[T]): T = ???
  override def setOption[T](option: xnio.Option[T], value: T): T = ???
  override def getReadThread: XnioExecutor = ???
  override def getWorker: XnioWorker = ???
  override def getIoThread: XnioIoThread = ???

  override def isOpen: Boolean = !closed.get()
  override def close(): Unit = {
    closed.set(true)
    if (closeListener != null) {
      closeListener.handleEvent(this)
    }
  }
}
