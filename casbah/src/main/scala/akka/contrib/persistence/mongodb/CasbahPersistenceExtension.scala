package akka.contrib.persistence.mongodb

import akka.actor.ActorSystem
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.WriteConcern

import scala.language.implicitConversions

object CasbahPersistenceDriver {
  import akka.contrib.persistence.mongodb.MongoPersistenceBase._
  
  implicit def convertListOfStringsToListOfServerAddresses(strings: List[String]): List[ServerAddress] =
    strings.map { url =>
    val Array(host, port) = url.split(":")
    new ServerAddress(host, port.toInt)
    }.toList

  implicit def convertWriteSafety2WriteConcern(writeSafety: WriteSafety): WriteConcern = writeSafety match {
    case ErrorsIgnored => WriteConcern.None
    case Unacknowledged => WriteConcern.Normal
    case Acknowledged => WriteConcern.Safe
    case Journaled => WriteConcern.JournalSafe
    case ReplicaAcknowledged => WriteConcern.ReplicasSafe
  }
}

trait CasbahPersistenceDriver extends MongoPersistenceDriver with MongoPersistenceBase {
  import akka.contrib.persistence.mongodb.CasbahPersistenceDriver._
  
  // Collection type
  type C = MongoCollection

  private[this] lazy val db =
    userPass.map {
      case (u,p) => authenticated(u,p)
    }.getOrElse(unauthenticated())(mongoDbName)

  private[this] def authenticated(user: String, password: String) =
    MongoClient(mongoUrl,MongoCredential.createMongoCRCredential(user,mongoDbName,password.toCharArray) :: Nil)

  private[this] def unauthenticated() = MongoClient(mongoUrl)


  
  private[mongodb] def collection(name: String) = db(name)
  private[mongodb] def journalWriteConcern: WriteConcern = journalWriteSafety
  private[mongodb] def snapsWriteConcern: WriteConcern = snapsWriteSafety
}

class CasbahMongoDriver(val actorSystem: ActorSystem) extends CasbahPersistenceDriver

class CasbahPersistenceExtension(val actorSystem: ActorSystem) extends MongoPersistenceExtension {
  private[this] lazy val driver = new CasbahMongoDriver(actorSystem)
  private[this] lazy val _journaler = new CasbahPersistenceJournaller(driver) with MongoPersistenceJournalMetrics {
    override def driverName = "casbah"
  }
  private[this] lazy val _snapshotter = new CasbahPersistenceSnapshotter(driver)
  
  override def journaler = _journaler
  override def snapshotter = _snapshotter
}
