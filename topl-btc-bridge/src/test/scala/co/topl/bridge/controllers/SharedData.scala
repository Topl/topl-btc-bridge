package co.topl.bridge.controllers

import co.topl.shared.ToplPrivatenet

trait SharedData {

  val testKey =
    "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a"

  val testHash =
    "497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2"

  val testInputTx =
    "08d9a63c8e05945d83dd8460d9418e506beb041dd719104108fc53733e8de1d0"

  val peginWalletFile = "src/test/resources/pegin-wallet.json"

  val walletFile = "src/test/resources/wallet.json"

  val toplWalletFile = "src/test/resources/topl-wallet.json"

  val testSecret = "secret"

  val testPassword = "password"

  val testToplPassword = "test"

  val testTx =
    "02000000000101d0e18d3e7353fc08411019d71d04eb6b508e41d96084dd835d94058e3ca6d908000000000000000000016879070000000000160014"


  val testBlockToRecover = 100

  val testToplNetworkId = ToplPrivatenet

}
