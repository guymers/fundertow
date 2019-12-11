package fundertow.zio

import java.util.concurrent.RejectedExecutionException

import org.xnio.XnioWorker
import zio.internal.ExecutionMetrics
import zio.internal.Executor
import zio.internal.Platform
import zio.internal.PlatformLive

object XnioPlatform {

  def create(worker: XnioWorker): Platform = {
    val executor = createExecutor(worker)
    PlatformLive.fromExecutor(executor)
  }

  private def createExecutor(worker: XnioWorker) = new Executor {

    override val yieldOpCount: Int = PlatformLive.defaultYieldOpCount

    override val metrics: Option[ExecutionMetrics] = None

    override def submit(runnable: Runnable): Boolean =
      try {
        worker.execute(runnable)
        true
      } catch {
        case _: RejectedExecutionException => false
      }

    override val here = false
  }

}
