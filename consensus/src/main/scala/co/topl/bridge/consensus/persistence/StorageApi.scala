package co.topl.bridge.consensus.persistence

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import com.google.common.io.BaseEncoding

import java.sql.DriverManager
trait StorageApi[F[_]] {
  def insertBlockchainEvent(event: BlockchainEvent): F[Unit]
  def initializeStorage(): F[Unit]
}

object StorageApiImpl {

  def createResource[F[_]: Sync](fileName: String) = Resource
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

    val statementResource =
      Resource.make(Sync[F].blocking(conn.createStatement()))(stmnt =>
        Sync[F].blocking(stmnt.close())
      )

    def insertionStmnt(streamId: String, serializedEvent: String) =
      s"INSERT INTO events (stream_id, event_data) VALUES ('$streamId', '${serializedEvent}')"

    val createEvtTableStmnt =
      "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY," +
        " stream_id TEXT NOT NULL,  event_data)"

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
      Sync[F].blocking(
        stmnt.execute(createEvtTableStmnt)
      )
    }

  }

}
