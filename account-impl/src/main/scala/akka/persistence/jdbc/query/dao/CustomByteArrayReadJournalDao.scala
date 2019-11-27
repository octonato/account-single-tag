package akka.persistence.jdbc.query.dao

import akka.NotUsed
import akka.persistence.PersistentRepr
import akka.persistence.jdbc.config.{ JournalTableConfiguration, ReadJournalConfig }
import akka.persistence.jdbc.journal.dao.ByteArrayJournalSerializer
import akka.persistence.jdbc.serialization.FlowPersistentReprSerializer
import akka.serialization.Serialization
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import slick.jdbc.JdbcProfile
import slick.jdbc.GetResult
import slick.jdbc.JdbcBackend._


import scala.collection.immutable._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class CustomByteArrayReadJournalDao(
  val db: Database,
  val profile: JdbcProfile,
  val readJournalConfig: ReadJournalConfig,
  serialization: Serialization)(implicit ec: ExecutionContext, mat: Materializer) extends BaseByteArrayReadJournalDao {

  import profile.api._

  val queries = new CustomReadJournalQueries(profile, readJournalConfig)
  val serializer = new ByteArrayJournalSerializer(serialization, readJournalConfig.pluginConfig.tagSeparator)

  override def eventsByTag(tag: String, offset: Long, maxOffset: Long, max: Long): Source[Try[(PersistentRepr, Set[String], Long)], NotUsed] =
    Source.fromPublisher(db.stream(queries.eventsByTag(tag, offset, maxOffset, max).result))
      .via(serializer.deserializeFlow)
}

class CustomReadJournalQueries(override val profile: JdbcProfile, override val readJournalConfig: ReadJournalConfig) extends ReadJournalQueries(profile, readJournalConfig) {

  import profile.api._

  private def _eventsByTag(tag: Rep[String],
                           offset: ConstColumn[Long],
                           maxOffset: ConstColumn[Long],
                           max: ConstColumn[Long]) = {
    baseTableQuery()
      .filter(_.tags === tag)
      .sortBy(_.ordering.asc)
      .filter(row => row.ordering > offset && row.ordering <= maxOffset)
      .take(max)
  }

  override val eventsByTag = Compiled(_eventsByTag _)


  private def baseTableQuery() =
    if (readJournalConfig.includeDeleted) JournalTable
    else JournalTable.filter(_.deleted === false)

}