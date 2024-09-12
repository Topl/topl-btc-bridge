package co.topl.bridge.stubs

import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import cats.effect.IO
import co.topl.brambl.dataApi.WalletFellowship

class BaseFellowshipStorageAlgebra extends FellowshipStorageAlgebra[IO] {

  override def findFellowships(): IO[Seq[WalletFellowship]] = ???

  override def addFellowship(walletEntity: WalletFellowship): IO[Int] = ???

}
