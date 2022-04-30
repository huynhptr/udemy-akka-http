package part3_highlevelserver

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout
import spray.json._

import scala.concurrent.duration._

case class Player(nickname: String, characterClass: String, level: Int)

object GameAreaMap {
  case object GetAllPlayers
  case class GetPlayer(nickname: String)
  case class GetPlayersByClass(characterClass: String)
  case class AddPlayer(player: Player)
  case class RemovePlayer(player: Player)
  case object OperationSuccess
}

class GameAreaMap extends Actor with ActorLogging {
  import GameAreaMap._

  var players = Map[String, Player]()

  override def receive: Receive = {
    case GetAllPlayers =>
      log.info("Getting all players")
      sender() ! players.values.toList
    case GetPlayer(nickname) =>
      log.info(s"Getting player with nickname $nickname")
      sender() ! players.get(nickname)
    case GetPlayersByClass(characterClass) =>
      log.info(s"Getting all players with the character class $characterClass")
      sender() ! players.values.toList.filter(_.characterClass == characterClass)
    case AddPlayer(player) =>
      log.info(s"Trying to add player $player")
      players = players + (player.nickname->player)
      sender() ! OperationSuccess
    case RemovePlayer(player) =>
      log.info(s"Removing player $player")
      players = players - player.nickname
      sender() ! OperationSuccess
  }
}

trait PlayerJsonProtocol extends DefaultJsonProtocol {
  implicit val playerFormat = jsonFormat3(Player)
}

object MarshallingJSON extends App with PlayerJsonProtocol with SprayJsonSupport {
  implicit val system = ActorSystem("MarshallingJSON")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher
  import GameAreaMap._

  val rtjGameMap = system.actorOf(Props[GameAreaMap], "rtjGameAreaMap")
  val playersList = List(
    Player("martin killz u", "Warrior", 70),
    Player("rolandbraveheart007", "Elf", 67),
    Player("daniel_rock03", "Wizard", 30)
  )

  playersList.foreach { player =>
    rtjGameMap ! AddPlayer(player)
  }

  /*
  - GET /api/player: return all the players in the map as JSON
  - GET /api/player/(nickname): return player with the nickname as JSON
  - GET /api/player?nickname=X does the same
  - GET /api/player/class/(charClass): return all players with the given character class
  - POST /api/player with JSON payload, adds the player to the map
  - (exercise) DELETE /api/player with JSON payload, removes the player from the map
   */
  implicit val timeout = Timeout(2 seconds)
  val rtjvmGameRouteSkeleton =
    pathPrefix("api" / "player") {
      get {
        path("class" / Segment) {characterClass =>
          val playersByClassFuture = (rtjGameMap ? GetPlayersByClass(characterClass)).mapTo[List[Player]]
          complete(playersByClassFuture)
        } ~
        (path(Segment) | parameter('nickname.as[String])) {nickname =>
          complete((rtjGameMap ? GetPlayer(nickname)).mapTo[Option[Player]])
        } ~
        pathEndOrSingleSlash {
          complete((rtjGameMap ? GetAllPlayers).mapTo[List[Player]])
        }
      } ~
      post {
        entity(as[Player]) { player =>
          complete((rtjGameMap ? AddPlayer(player)).map(_ => StatusCodes.OK))
        }
      } ~
      delete {
        entity(as[Player]) { player =>
          complete((rtjGameMap ? RemovePlayer(player)).map(_ => StatusCodes.OK))
        }
      }
    }
  Http().bindAndHandle(rtjvmGameRouteSkeleton, "localhost", 8080)
}
