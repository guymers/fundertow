package testzio

object Printer {

  def colored(tr: TestResult): String => String = str => {
    val color = tr.result match {
      case Result.Pass => Console.GREEN
      case _: Result.Fail => Console.RED
      case _: Result.Error => Console.RED
    }
    s"$color$str${Console.RESET}"
  }

  def verbose(tr: TestResult): String = {
    val str = tr.result match {
      case Result.Pass => "Pass"
      case r: Result.Fail => r.reason.fold("Fail")(reason => s"Fail because $reason")
      case r: Result.Error => s"Error ${util.stacktraceToString(r.t)}"
    }
    s"${name(tr.name)}: $str"
  }

  def simple(tr: TestResult): String = {
    val str = tr.result match {
      case Result.Pass => ""
      case r: Result.Fail => r.reason.fold("Fail")(reason => s"Fail because $reason")
      case r: Result.Error => s"Error ${r.t.getMessage}"
    }
    s"${name(tr.name)}: $str"
  }

  def name(name: ::[String]): String = name.reverse.mkString("[", " -> ", "]")
}
