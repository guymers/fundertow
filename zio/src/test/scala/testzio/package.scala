import zio.ZIO
import zio.ZManaged
import zio.console.Console

package object testzio {

  type Printer = TestResult => ZIO[Console, Nothing, Unit]
  type Suite[-Env] = Printer => ZIO[Env, Nothing, TestResults]
  type Test[-Env, R] = (R, List[String]) => Suite[Env]

  def suite[Env](name: String)(t: Test[Env, Unit], ts: Test[Env, Unit]*): Suite[Env] = printer => {
    val scope = List(name)
    group(t, ts: _*)((), scope)(printer)
  }

  def managedSuite[Env0, Env <: Env0, R](
    m: ZManaged[Env0, Nothing, R]
  )(name: String)(t: Test[Env, R], ts: Test[Env, R]*): Suite[Env] = printer => {
    m.use { r =>
      val scope = List(name)
      group(t, ts: _*)(r, scope)(printer)
    }
  }

  def group[Env, R](t: Test[Env, R], ts: Test[Env, R]*): Test[Env, R] = (r, scope) => printer => {
    t(r, scope)(printer).zipPar(ZIO.foreachPar(ts)(_(r, scope)(printer))).map { case (tr, trs) =>
      TestResults.combineMany(tr, trs)
    }
  }

  def section[Env, R](name: String)(t: Test[Env, R], ts: Test[Env, R]*): Test[Env, R] = (r, scope) => printer => {
    val newScope = name :: scope
    group(t, ts: _*)(r, newScope)(printer)
  }

  def test[Env <: Console, R](name: String)(run: R => ZIO[Env, Throwable, Result]): Test[Env, R] = (r, scope) => printer => {
    run(r).catchAll { t =>
      ZIO.succeed(Result.error(t))
    }.flatMap { result =>
      val tr = TestResult(::(name, scope), result)
      printer(tr).const(tr.toResults)
    }
  }

  def assert(b: Boolean): Result = {
    if (b) Result.pass else Result.fail
  }

  def assert(b: Boolean, reason: String): Result = {
    if (b) Result.pass else Result.failWithReason(reason)
  }

  def fail(reason: String): Result = {
    Result.failWithReason(reason)
  }
}
