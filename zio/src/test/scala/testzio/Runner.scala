package testzio

import zio.ZIO
import zio.console
import zio.clock

trait Runner extends zio.App {

  def suites: List[Suite[Environment]]

  def output(tr: TestResult): String = {
    Printer.colored(tr)(Printer.verbose(tr))
  }

  val printer: Printer = tr => {
    val s = output(tr)
    if (s.isEmpty) ZIO.unit else console.putStrLn(s)
  }

  override def run(args: List[String]): ZIO[Environment, Nothing, Int] = for {
    startTime <- clock.nanoTime
    results <- ZIO.mergeAllPar(suites.map(_(printer)))(TestResults.empty)(TestResults.combine)
    endTime <- clock.nanoTime
    _ <- console.putStrLn {
      val total = results.passed + results.failed + results.errors
      s"Total: $total"
    }
    _ <- console.putStrLn {
      s"Passed: ${results.passed}  Failed: ${results.failed}  Errors: ${results.errors}"
    }
    _ <- console.putStrLn {
      val duration = java.time.Duration.ofNanos(endTime - startTime)
      s"Testing took " concat String.valueOf(duration.toMillis) concat "ms"
    }
  } yield {
    if (results.successful) 0 else 1
  }
}
