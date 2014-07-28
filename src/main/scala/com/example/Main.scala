package com.example

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.contrib.pattern.{ClusterSharding, ClusterSingletonManager, ClusterSingletonProxy}
import akka.pattern.ask
import akka.util.Timeout
import spray.http._
import spray.routing.authentication.{BasicAuth, UserPass}
import spray.routing.{Directives, Route, SimpleRoutingApp}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Try}

trait RequestTimeout {
  implicit val timeout = Timeout(10 seconds)

  implicit def executionContext: ExecutionContextExecutor
}

trait UserAuthenticator extends RequestTimeout {
  def userRegion: ActorRef

  def authenticator(userPass: Option[UserPass]): Future[Option[String]] = userPass match {
    case None => Future {
      None
    }
    case Some(_) => (userRegion ? User.Authenticate(userPass)).mapTo[Option[String]]
  }
}

trait SecurityRoute extends Directives with RequestTimeout {

  import com.example.UserView.JsonMarshaller._

  def userRegion: ActorRef

  def userView: ActorRef

  def securityRoute: Route = {
    pathPrefix("users") {
      path(Segment) { name =>
        get {
          complete {
            (userView ? UserView.GetUserByName(name)).mapTo[Option[UserView.UserWithoutPassword]]
          }
        } ~
          delete {
            complete {
              userRegion ! User.Delete(name)
              StatusCodes.NoContent
            }
          }
      }
    } ~
      get {
        complete {
          (userView ? UserView.GetAllUsers).mapTo[List[UserView.UserWithoutPassword]]
        }
      } ~
      post {
        entity(as[UserView.UserWithPassword]) { user =>
          complete {
            userRegion ! User.Create(user.username, user.password)
            StatusCodes.NoContent
          }
        }
      } ~
      put {
        entity(as[UserView.UserWithPassword]) { user =>
          complete {
            userRegion ! User.Update(user.username, user.password)
            StatusCodes.NoContent
          }
        }
      }
  }
}

object Main extends App with SimpleRoutingApp with UserAuthenticator with SecurityRoute {
  implicit val system = ActorSystem("ClusterSystem")
  implicit val executionContext = system.dispatcher

  system.actorOf(ClusterSingletonManager.props(
    singletonProps = UserView.props,
    singletonName = "userView",
    terminationMessage = PoisonPill,
    role = None),
    name = "userViewSingleton")

  val userView = system.actorOf(ClusterSingletonProxy.props("/user/userViewSingleton/userView", None), "userViewProxy")

  ClusterSharding(system).start(
    typeName = User.shardName,
    entryProps = Some(User.props(userView)),
    idExtractor = User.idExtractor,
    shardResolver = User.shardResolver)

  val userRegion = ClusterSharding(system).shardRegion(User.shardName)

  val config = Config(system)

  startServer(interface = config.bindAddress, port = config.bindPort) {
    pathPrefix("api") {
      securityRoute
    } ~
      pathPrefix("web") {
        getFromResourceDirectory("web")
      } ~
      authenticate(BasicAuth(authenticator _, realm = "secure site")) { username =>
        path("secure") {
          complete("Welcome!")
        }
      }
  }
}
