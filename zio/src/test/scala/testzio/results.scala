package testzio

sealed abstract class Result
object Result {
  case object Pass extends Result
  final class Fail private[testzio](val reason: Option[String]) extends Result
  final class Error private[testzio](val t: Throwable) extends Result

  val pass: Result = Pass
  val fail: Result = new Fail(None)
  def failWithReason(reason: String): Result = new Fail(Some(reason))
  def error(t: Throwable): Result = new Error(t)
}

final case class TestResult(name: ::[String], result: Result) {
  def toResults: TestResults = result match {
    case Result.Pass => TestResults(passed = 1, failed = 0, errors = 0)
    case _: Result.Fail => TestResults(passed = 0, failed = 1, errors = 0)
    case _: Result.Error => TestResults(passed = 0, failed = 0, errors = 1)
  }
}

final case class TestResults(passed: Int, failed: Int, errors: Int) {
  val successful: Boolean = failed == 0 && errors == 0
}
object TestResults {
  val empty = TestResults(0, 0, 0)

  def combine(r1: TestResults, r2: TestResults): TestResults = TestResults(
    passed = r1.passed + r2.passed,
    failed = r1.failed + r2.failed,
    errors = r1.errors + r2.errors,
  )

  def combineMany(r: TestResults, rs: List[TestResults]): TestResults = {
    var passed = r.passed
    var failed = r.failed
    var errors = r.errors
    rs.foreach { r =>
      passed = passed + r.passed
      failed = failed + r.failed
      errors = errors + r.errors
    }
    TestResults(passed, failed, errors)
  }
}
