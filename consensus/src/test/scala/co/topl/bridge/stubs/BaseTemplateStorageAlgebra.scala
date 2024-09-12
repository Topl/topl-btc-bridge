package co.topl.bridge.stubs

import co.topl.brambl.dataApi.TemplateStorageAlgebra
import cats.effect.IO
import co.topl.brambl.dataApi.WalletTemplate

class BaseTemplateStorageAlgebra extends TemplateStorageAlgebra[IO] {

  override def findTemplates(): IO[Seq[WalletTemplate]] = ???

  override def addTemplate(walletTemplate: WalletTemplate): IO[Int] = ???

}
