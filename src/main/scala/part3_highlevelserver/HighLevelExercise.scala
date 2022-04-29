package part3_highlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import spray.json._

import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class Person(pin: Int, name: String)

trait PersonJsonProtocol extends DefaultJsonProtocol {
  implicit val personJsonFormat = jsonFormat2(Person)
}

object HighLevelExercise extends App with PersonJsonProtocol {
  implicit val system = ActorSystem("HighLevelExercise")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  /*
  Exercise:
  - GET /api/people: retrieves all the people you have registered
  - GET /api/people/pin: retrieve the person with that PIN. return as a JSON
  - GET /api/people?pin=X (same as above)
  - POST /api/people with a JSON payload denoting a Person, add that Person to your database
    - extract the HTTP request's payload (entity)
      - extract the request
      - process the entity's data
   */

  var people = List[Person](
    Person(1, "Alice"),
    Person(2, "Bob"),
    Person(3, "Charlie")
  )

  val peopleApiRoute =
    pathPrefix("api" / "people") {
      (post & pathEndOrSingleSlash & extractRequest) {
//        case HttpRequest(_, _, _, entity, _) =>
//          complete(
//            entity.toStrict(3 seconds)
//            .map(_.data.utf8String.parseJson.convertTo[Person])
//            .map {person =>
//              people = people ++ List(person)
//              StatusCodes.OK
//            }.recover {
//              case _ => StatusCodes.InternalServerError
//            }
//          )
        request =>
          val entity = request.entity
          val strictEntityFuture = entity.toStrict(2 seconds)
          val personFuture = strictEntityFuture.map(_.data.utf8String.parseJson.convertTo[Person])

          onComplete(personFuture) {
            case Success(person) =>
              people = people :+ person
              complete(StatusCodes.OK)
            case Failure(exception) =>
              failWith(exception)
          }
      } ~
      get {
        (path(IntNumber) | parameter('pin.as[Int])) { (pin: Int) =>
          complete(people.find(_.pin == pin) match {
            case Some(ps) => HttpEntity(
              ContentTypes.`application/json`,
              ps.toJson.prettyPrint
            )
            case None => HttpResponse(StatusCodes.NoContent)
          })
        } ~
        pathEndOrSingleSlash {
          complete(
            HttpEntity(
              ContentTypes.`application/json`,
              people.toJson.prettyPrint
            )
          )
        }
      }
    }
  Http().bindAndHandle(peopleApiRoute, "localhost", 8080)
}
