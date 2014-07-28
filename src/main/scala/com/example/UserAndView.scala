package com.example

import java.util.Date
import akka.actor.Actor.Receive
import akka.contrib.pattern.ShardRegion
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol
import spray.routing.authentication.UserPass
import akka.actor._
import org.apache.shiro.crypto.SecureRandomNumberGenerator
import org.apache.shiro.util.ByteSource
import org.apache.shiro.crypto.hash.Sha512Hash
import akka.persistence.PersistentActor
import scala.concurrent.duration._

/**
 * The user entry
 */
object User {
  val shardName: String = "Users"

  val idExtractor: ShardRegion.IdExtractor = {
    case msg: Create => (msg.username, msg)
    case msg: Update => (msg.username, msg)
    case msg: Delete => (msg.username, msg)
    case msg @ Authenticate(Some(up)) => (up.user, msg)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case msg: Create => (math.abs(msg.username.hashCode) % 100).toString
    case msg: Update => (math.abs(msg.username.hashCode) % 100).toString
    case msg: Delete => (math.abs(msg.username.hashCode) % 100).toString
    case msg @ Authenticate(Some(up)) => (math.abs(up.user.hashCode) % 100).toString
  }

  // commands
  case class Create(username: String, password: String)
  case class Update(username: String, password: String)
  case class Delete(username: String)
  case class Authenticate(userpass: Option[UserPass])
  case object Stop

  // events
  case class Created(state: State)
  case class Updated(state: State)
  case class Deleted(userId: String)

  // state
  case class State(updated: Long, userId: String, passwordHash: String, passwordSalt: String)

  // create the actor
  def props(userView: ActorRef) = Props(new User(userView))

  // security logic
  def generateSalt: String = {
    val rng = new SecureRandomNumberGenerator
    val byteSourceSalt: ByteSource = rng.nextBytes
    byteSourceSalt.toHex
  }

  def generateHashedPassword(passwordText: String, passwordSalt: String, iterations: Int = 512) =  new Sha512Hash(passwordText, passwordSalt, iterations).toHex

  def generatePassword(password: String): (String, String) = {
    val passwordSalt = generateSalt
    val hashedPassword = generateHashedPassword(password, passwordSalt)
    (hashedPassword, passwordSalt)
  }
}

class User(userView: ActorRef) extends PersistentActor with ActorLogging {
  import User._
  import ShardRegion.Passivate

  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  // passivate the entity when no activity
  context.setReceiveTimeout(5 seconds)

  var state: Option[State] = None

  def handleUserCreated(evt: Created) {
    val Created(state) = evt
    this.state = Some(state)
  }

  def handleDeleted(evt: Deleted) {
    val Deleted(_) = evt
    this.state = None
  }

  def handleUserUpdated(evt: Updated) {
    val Updated(state) = evt
    this.state = Some(state)
  }

  override def receiveRecover: Receive = {
    case evt: Created =>
      log.info("Recovering entry: {}", evt)
      handleUserCreated(evt)
    case evt: Updated =>
      log.info("Recovering entry: {}", evt)
      handleUserUpdated(evt)
    case evt: Deleted =>
      log.info("Recovering entry: {}", evt)
      handleDeleted(evt)
  }

  override def receiveCommand: Receive = {
    case msg @ Create(username, password) if state == None =>
      log.info("Creating entry: {}", msg)
      val (passwordHash, passwordSalt) = generatePassword(password)
      val state = State(updated = new Date().getTime, userId = username, passwordHash = passwordHash, passwordSalt = passwordSalt)
      val evt = Created(state)
      persist(evt)(handleUserCreated)
      userView ! evt

    case msg @ Delete(username) if state != None =>
      log.info("Deleting entry: {}", msg)
      val state = None
      val evt = Deleted(username)
      persist(evt)(handleDeleted)
      userView ! evt

    case msg @ Update(username, password) if state != None =>
      log.info("Updating entry: {}", msg)
      val (passwordHash, passwordSalt) = generatePassword(password)
      val state = this.state.get.copy(updated = new Date().getTime, passwordHash = passwordHash, passwordSalt = passwordSalt)
      val evt = Updated(state)
      persist(evt)(handleUserUpdated)

    case msg @ Authenticate(userPass) =>
      log.info(s"Authenticating: {}", msg)
      sender ! userPass.flatMap { up =>
        state match {
          case Some(State(_, userId, passwordHash, passwordSalt)) =>
            val hashedPwd = generateHashedPassword(up.pass, passwordSalt)
            if(hashedPwd == passwordHash && up.user == userId) Some(up.user) else None
          case None => None
        }
      }

    // Passivate the Actor when timeout hits
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = Stop)
    case Stop           => context.stop(self)

    case _ @ msg => log.info("Receiving unknown msg: {}", msg)
  }
}

object UserView {
  def props = Props(new UserView())

  // business type
  case class UserWithoutPassword(username: String)
  case class UserWithPassword(username: String, password: String)

  // commands
  case object GetAllUsers
  case class GetUserByName(username: String)

  // state
  case class StateChanged(state: List[UserWithoutPassword])

  object JsonMarshaller extends DefaultJsonProtocol with SprayJsonSupport {
    implicit val userFormat = jsonFormat1(UserView.UserWithoutPassword)
    implicit val internetUserFormat = jsonFormat2(UserView.UserWithPassword)
  }
}

class UserView extends PersistentActor with ActorLogging {
  import User._
  import UserView._
  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  var userList = List.empty[UserWithoutPassword]

  def handleStateChange(evt: StateChanged) {
    this.userList = evt.state
    log.info("{}", userList)
  }

  override def receiveRecover: Receive = {
    case evt: StateChanged =>
      log.info("Recovering entry: {}", evt)
      handleStateChange(evt)
  }

  override def receiveCommand: Receive = {
    case msg @ Created(State(_, userId, _, _)) =>
      log.info("Created: {}", msg)
      val evt = StateChanged(UserWithoutPassword(userId) :: userList)
      persist(evt)(handleStateChange)

    case msg @ Deleted(userId) =>
      log.info("Deleted: {}", msg)
      val list = userList.filterNot { _.username == userId }
      val evt = StateChanged(list)
      persist(evt)(handleStateChange)

    case GetAllUsers =>
      log.info("Getting all users")
      sender() ! userList

    case msg @ GetUserByName(username) =>
      log.info("Get user by name: {}", msg)
      sender ! userList.find { user => user.username.equals(username) }

    case msg @ _ => log.warning("Could not handle message: {}", msg)
  }
}