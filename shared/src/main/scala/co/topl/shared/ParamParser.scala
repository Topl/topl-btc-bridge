package co.topl.shared

import org.bitcoins.core.currency.CurrencyUnit

import org.bitcoins.core.currency.SatoshisLong
import scala.util.Try
import scala.util.Success
import scala.util.Failure

object ParamParser {

  implicit val networkRead: scopt.Read[BitcoinNetworkIdentifiers] =
    scopt.Read
      .reads(BitcoinNetworkIdentifiers.fromString(_))
      .map(_ match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException(
            "Invalid network. Possible values: mainnet, testnet, regtest"
          )
      })

  implicit val toplNetworkRead: scopt.Read[ToplNetworkIdentifiers] =
    scopt.Read
      .reads(ToplNetworkIdentifiers.fromString(_))
      .map(_ match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException(
            "Invalid network. Possible values: mainnet, testnet, private"
          )
      })
  implicit val currencyUnit: scopt.Read[CurrencyUnit] =
    scopt.Read
      .reads(x => Try(x.toLong.satoshis))
      .map(_ match {
        case Success(v) => v
        case Failure(_) =>
          throw new IllegalArgumentException(
            "Could not conver value to satoshi"
          )
      })

}
