package fundertow.zio

import java.util.concurrent.RejectedExecutionException

import org.xnio.XnioWorker
import zio.internal.ExecutionMetrics
import zio.internal.Executor
import zio.internal.Platform

object XnioPlatform {

  def create(worker: XnioWorker): Platform = {
    val executor = createExecutor(worker)
    Platform.fromExecutor(executor)
  }

  private def createExecutor(worker: XnioWorker): Executor = new Executor {

    override val yieldOpCount: Int = Platform.defaultYieldOpCount

    override val metrics: Option[ExecutionMetrics] = Some {
      new ExecutionMetrics {
        override def concurrency: Int = worker.getMXBean.getWorkerPoolSize
        override def capacity: Int = worker.getMXBean.getMaxWorkerPoolSize
        override def size: Int = worker.getMXBean.getWorkerQueueSize
        override def enqueuedCount: Long = 0
        override def dequeuedCount: Long = 0
        override def workersCount: Int = worker.getMXBean.getBusyWorkerThreadCount
      }
    }

    override def submit(runnable: Runnable): Boolean = {
      try {
        worker.execute(runnable)
        true
      } catch {
        case _: RejectedExecutionException => false
      }
    }
  }

}
