package part3_highlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtSprayJson}
import spray.json._

import java.util.Optional
import java.util.concurrent.TimeUnit
import scala.util.{Failure, Success}

object SecurityDomain extends DefaultJsonProtocol {
  case class LoginRequest(username: String, password: String)
  implicit val loginRequestFormat = jsonFormat2(LoginRequest)
}
object JwtAuthorization extends App with SprayJsonSupport{
  implicit val system = ActorSystem("JwtAuthorization")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher
  import SecurityDomain._

  val superSecretPasswordDB = Map(
    "admin" -> "admin",
    "daniel" -> "Rockthejvm1!"
  )

  val algorithm = JwtAlgorithm.HS256
  val secretKey = "rockthejvmsecret"

  def checkPassword(username: String, password: String): Boolean =
    superSecretPasswordDB.contains(username) && superSecretPasswordDB(username) == password

  def createToken(username: String, expirationPeriodInDays: Int): String = {
    val claims = JwtClaim(
      expiration = Some(System.currentTimeMillis() / 1000 + TimeUnit.DAYS.toSeconds(expirationPeriodInDays)),
      issuedAt = Some(System.currentTimeMillis() / 1000),
      issuer = Some("rockthejvm.com")
    )

    JwtSprayJson.encode(claims, secretKey, algorithm) // JWT String
  }

  def isTokenExpired(token: String): Boolean = JwtSprayJson.decode(token, secretKey, Seq(algorithm)) match {
    case Success(claims) => claims.expiration.getOrElse(0L) < System.currentTimeMillis() / 1000
    case Failure(_) => true
  }

  def isTokenValid(token: String): Boolean = JwtSprayJson.isValid(token, secretKey, Seq(algorithm))

  val loginRoute =
    post {
      entity(as[LoginRequest]) {
        case LoginRequest(username, password) if checkPassword(username, password) =>
          val token = createToken(username, 1)
          respondWithHeader(RawHeader("Access-Token", token)) {
            complete(StatusCodes.OK)
          }
        case _ => complete(StatusCodes.Unauthorized)
      }
    }

  val authenticatedRoute =
    (path("secureEndpoint") & get) {
      optionalHeaderValueByName("Authorization") {
        case Some(token) =>
          if (!isTokenValid(token)) {
            complete(HttpResponse(StatusCodes.Unauthorized, entity = "Token invalid."))
          } else if (isTokenExpired(token)) {
            complete(HttpResponse(StatusCodes.Unauthorized, entity = "Token expired."))
          } else {
            complete("User accessed authorized endpoint!")
          }
        case _ =>
          complete(HttpResponse(StatusCodes.Unauthorized, entity = "No token provided!"))
      }
    }

  val route = loginRoute ~ authenticatedRoute

  Http().bindAndHandle(route, "localhost", 8080)
}
