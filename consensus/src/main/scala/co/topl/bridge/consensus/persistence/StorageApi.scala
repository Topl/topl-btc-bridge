package co.topl.bridge.consensus.persistence

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.PeginSessionState
import co.topl.bridge.consensus.managers.PeginSessionInfo
import co.topl.bridge.consensus.managers.SessionInfo
import co.topl.bridge.consensus.monitor.BlockchainEvent
import co.topl.bridge.consensus.utils.MiscUtils
import com.google.common.io.BaseEncoding

import java.sql.DriverManager

trait StorageApi[F[_]] {

  def insertNewSession(
      sessionId: String,
      sessionInfo: SessionInfo
  ): F[Unit]

  def getSession(sessionId: String): F[Option[SessionInfo]]

  def updateSession(
      sessionId: String,
      sessionInfo: SessionInfo
  ): F[Unit]

  def insertBlockchainEvent(event: BlockchainEvent): F[Unit]

  def initializeStorage(): F[Unit]
}

object StorageApiImpl {

  private def createResource[F[_]: Sync](fileName: String) = Resource
    .make(
      {
        // Without this line, repeated runs fail with "No suitable driver found for jdbc:sqlite:..."
        Class.forName("org.sqlite.JDBC")
        Sync[F].delay(
          DriverManager.getConnection(
            s"jdbc:sqlite:${fileName}"
          )
        )
      }
    )(conn => Sync[F].delay(conn.close()))

  def make[F[_]: Sync](fileName: String): Resource[F, StorageApi[F]] = for {
    conn <- createResource(fileName)
  } yield new StorageApi[F] {

    override def insertNewSession(
        sessionId: String,
        sInfo: SessionInfo
    ): F[Unit] = {
      MiscUtils.sessionInfoPeginPrism.getOption(
        sInfo
      ) match {
        case None =>
          Sync[F].raiseError(
            new Exception(
              "SessionInfo is not a PeginSessionInfo"
            )
          )
        case Some(sessionInfo) =>
          val insertSessionStmnt =
            s"INSERT INTO sessions_view (" +
              "session_id, " +
              "session_type, " +
              "btc_wallet_idx, " +
              "bridge_wallet_idx, " +
              "mint_template, " +
              "redeem_address, " +
              "escrow_address, " +
              "script_asm, " +
              "sha256, " +
              "min_height, " +
              "max_height, " +
              "claim_address, " +
              "minting_status " +
          " ) VALUES " +
            "(" +
            s"'${sessionId}'," +
            "'pegin'," +
            s"${sessionInfo.btcPeginCurrentWalletIdx}," +
            s"${sessionInfo.btcBridgeCurrentWalletIdx}," +
            s"'${sessionInfo.mintTemplateName}'," +
            s"'${sessionInfo.redeemAddress}'," +
            s"'${sessionInfo.escrowAddress}'," +
            s"'${sessionInfo.scriptAsm}'," +
            s"'${sessionInfo.sha256}'," +
            s"${sessionInfo.minHeight}," +
            s"${sessionInfo.maxHeight}," +
            s"'${sessionInfo.claimAddress}'," +
            s"'${sessionInfo.mintingBTCState.toString()}'" +
            ")"
          import cats.implicits._
          statementResource.use { stmnt =>
            Sync[F]
              .blocking(
                stmnt.execute(insertSessionStmnt)
              )
              .void
              .handleErrorWith { e =>
                Sync[F].delay(e.printStackTrace()) >>
                  Sync[F].raiseError(
                    new Exception(
                      s"Failed to insert session into database: ${e.getMessage}"
                    )
                  )
              }
          }
      }
    }

    def getSession(sessionId: String): F[Option[SessionInfo]] = {
      val selectSessionStmnt =
        s"SELECT * FROM sessions_view WHERE session_id = '${sessionId}'"
      import cats.implicits._
      statementResource.use { stmnt =>
        Sync[F]
          .blocking(
            stmnt.executeQuery(selectSessionStmnt)
          )
          .map { rs =>
            if (rs.next()) {
              if (rs.getString("session_type") != "pegin") {
                throw new Exception(
                  "Session type is not pegin"
                )
              } else
                Some(
                  PeginSessionInfo(
                    rs.getInt("btc_wallet_idx"),
                    rs.getInt("bridge_wallet_idx"),
                    rs.getString("mint_template"),
                    rs.getString("redeem_address"),
                    rs.getString("escrow_address"),
                    rs.getString("script_asm"),
                    rs.getString("sha256"),
                    rs.getLong("min_height"),
                    rs.getLong("max_height"),
                    rs.getString("claim_address"),
                    PeginSessionState
                      .withName(rs.getString("minting_status"))
                      .get
                  ): SessionInfo
                )
            } else {
              None
            }
          }
          .handleErrorWith { e =>
            Sync[F].delay(e.printStackTrace()) >>
              Sync[F].raiseError(
                new Exception(
                  s"Failed to get session from database: ${e.getMessage}"
                )
              )
          }
      }
    }

    override def updateSession(
        sessionId: String,
        sessionInfo: SessionInfo
    ): F[Unit] = {
      MiscUtils.sessionInfoPeginPrism.getOption(
        sessionInfo
      ) match {
        case None =>
          Sync[F].raiseError(
            new Exception(
              "SessionInfo is not a PeginSessionInfo"
            )
          )
        case Some(sessionInfo) =>
          val updateSessionStmnt =
            s"UPDATE sessions_view SET " +
              s"btc_wallet_idx = ${sessionInfo.btcPeginCurrentWalletIdx}, " +
              s"bridge_wallet_idx = ${sessionInfo.btcBridgeCurrentWalletIdx}, " +
              s"mint_template = '${sessionInfo.mintTemplateName}', " +
              s"redeem_address = '${sessionInfo.redeemAddress}', " +
              s"escrow_address = '${sessionInfo.escrowAddress}', " +
              s"script_asm = '${sessionInfo.scriptAsm}', " +
              s"sha256 = '${sessionInfo.sha256}', " +
              s"min_height = ${sessionInfo.minHeight}, " +
              s"max_height = ${sessionInfo.maxHeight}, " +
              s"claim_address = '${sessionInfo.claimAddress}', " +
              s"minting_status = '${sessionInfo.mintingBTCState.toString()}' " +
              s"WHERE session_id = '${sessionId}'"
          import cats.implicits._
          statementResource.use { stmnt =>
            Sync[F]
              .blocking(
                stmnt.execute(updateSessionStmnt)
              )
              .void
              .handleErrorWith { e =>
                Sync[F].delay(e.printStackTrace()) >>
                  Sync[F].raiseError(
                    new Exception(
                      s"Failed to update session in database: ${e.getMessage}"
                    )
                  )
              }
          }
      }
    }

    val statementResource =
      Resource.make(Sync[F].blocking(conn.createStatement()))(stmnt =>
        Sync[F].blocking(stmnt.close())
      )

    def insertionStmnt(streamId: String, serializedEvent: String) =
      s"INSERT INTO events (stream_id, event_data) VALUES ('$streamId', '${serializedEvent}')"

    val createEvtTableStmnt =
      "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY," +
        " stream_id TEXT NOT NULL,  event_data TEXT NOT NULL)"

    val createSessionTableStmt =
      "CREATE TABLE IF NOT EXISTS sessions_view (" +
        "session_id     TEXT PRIMARY KEY, " +
        "session_type   TEXT NOT NULL, " +
        "btc_wallet_idx INTEGER NOT NULL, " +
        "bridge_wallet_idx INTEGER NOT NULL, " +
        "mint_template  TEXT NOT NULL, " +
        "redeem_address        TEXT NOT NULL, " +
        "escrow_address        TEXT NOT NULL, " +
        "script_asm            TEXT NOT NULL, " +
        "sha256                TEXT NOT NULL, " +
        "min_height            INTEGER NOT NULL, " +
        "max_height            INTEGER NOT NULL, " +
        "claim_address         TEXT NOT NULL, " +
        "minting_status TEXT NOT NULL" + 
    ")"

    val BLOCKCHAIN_EVENT_ID = "blockchain_event"

    override def insertBlockchainEvent(
        event: BlockchainEvent
    ): F[Unit] =
      statementResource.use { stmnt =>
        import cats.implicits._
        val serializedEvent = toProtobuf(event).toByteArray
        val encodedEvent = BaseEncoding
          .base64()
          .encode(serializedEvent)
        Sync[F]
          .blocking(
            stmnt.execute(insertionStmnt(BLOCKCHAIN_EVENT_ID, encodedEvent))
          )
          .void
          .handleErrorWith { e =>
            Sync[F].delay(e.printStackTrace()) >>
              Sync[F].raiseError(
                new Exception(
                  s"Failed to insert event into database: ${e.getMessage}"
                )
              )
          }
      }

    override def initializeStorage(): F[Unit] = statementResource.use { stmnt =>
      import cats.implicits._
      Sync[F].blocking(
        stmnt.execute(createEvtTableStmnt)
      ) >>
        Sync[F].blocking(
          stmnt.execute(createSessionTableStmt)
        )
    }

  }

}
