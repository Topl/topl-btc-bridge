package co.topl.bridge.consensus

import org.bitcoins.core.currency.CurrencyUnit

import org.bitcoins.core.currency.SatoshisLong
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import co.topl.brambl.utils.Encoding
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import com.google.protobuf.ByteString

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

  implicit val groupIdRead: scopt.Read[GroupId] =
    scopt.Read.reads { x =>
      val array = Encoding.decodeFromHex(x).toOption match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException("Invalid group id")
      }
      GroupId(ByteString.copyFrom(array))
    }

  implicit val seriesIdRead: scopt.Read[SeriesId] =
    scopt.Read.reads { x =>
      val array = Encoding.decodeFromHex(x).toOption match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException("Invalid series id")
      }
      SeriesId(ByteString.copyFrom(array))
    }

}
