package fundertow.zio

sealed trait FundertowException

final case class ServerShutdownFailed(cause: Throwable) extends RuntimeException(
  "Failed to shutdown server", cause
) with FundertowException
