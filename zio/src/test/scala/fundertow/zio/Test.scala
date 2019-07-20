package fundertow.zio

import fundertow.zio.channels.StreamSourceChannelHelperTest
import testzio._

object Test extends Runner {
  override val suites: List[Suite[Environment]] = List(
    StreamSourceChannelHelperTest.Suite
  )
}
