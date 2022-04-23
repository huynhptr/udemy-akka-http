package part2_lowlevelserver

import akka.pattern.ask
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import part2_lowlevelserver.GuitarDB._
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._

case class Guitar(make: String, model: String, quantity: Int = 0)

object GuitarDB {
  case class CreateGuitar(guitar: Guitar)
  case class GuitarCreated(id: Int)
  case class FindGuitar(id: Int)
  case class AddGuitarQuantity(id: Int, quantity: Int)
  case object FindGuitarInStock
  case object FindGuitarOutOfStock
  case object FindAllGuitars
}
class GuitarDB extends Actor with ActorLogging {
  import GuitarDB._
  var guitars: Map[Int, Guitar] = Map.empty
  var currentGuitarId: Int = 0

  override def receive: Receive = {
    case FindAllGuitars =>
      log.info("Searching for all guitars")
      sender() ! guitars.values.toList

    case FindGuitar(id) =>
      log.info(s"Searching guitar by id: $id")
      sender() ! guitars.get(id)

    case CreateGuitar(guitar) =>
      log.info(s"Adding guitar: $guitar with id $currentGuitarId")
      guitars = guitars + (currentGuitarId->guitar)
      sender() ! GuitarCreated(currentGuitarId)
      currentGuitarId += 1

    case FindGuitarInStock =>
      log.info("Searching for guitars in stock")
      sender() ! guitars.values.filter(_.quantity != 0).toList

    case FindGuitarOutOfStock =>
      log.info("Searching for guitars out of stock")
      sender() ! guitars.values.filter(_.quantity == 0).toList

    case AddGuitarQuantity(id, quantity) =>
      val guitarOption = guitars.get(id)
      log.info(s"Trying to add $quantity guitars to inventory")
      sender() ! guitarOption.map { guitar =>
        val newGuitar = guitar.copy(quantity = guitar.quantity + quantity)
        guitars = guitars + (id->newGuitar)
        newGuitar
      }
  }
}

trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {
  implicit val guitarFormat = jsonFormat3(Guitar)
}

object LowLevelRest extends App with GuitarStoreJsonProtocol {
  implicit val system = ActorSystem("LowLevelRest")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  /*
  GET on localhost:8080/api/guitar => ALL the guitars in the store
  Get on localhost:8080/api/guitar?id=X => fetches the guitar associated with id X
  POST on localhost:8080/api/guitar => insert the guitar into the store
   */

  //JSON -> marshalling
  val simpleGuitar = Guitar("Fender", "Stratocaster")
//  println(simpleGuitar.toJson.prettyPrint)

  //unmarshalling
  val simpleGuitarJsonString =
    """
      |{
      |  "make": "Fender",
      |  "model": "Stratocaster"
      |}
      |""".stripMargin
//  println(simpleGuitarJsonString.parseJson.convertTo[Guitar])

  /*
  setup
   */
  val guitarDb = system.actorOf(Props[GuitarDB],"LowLevelGuitarDB")
  val guitarList = List(
    Guitar("Fender", "Stratocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )
  guitarList.foreach(guitar => guitarDb ! CreateGuitar(guitar))
  /*
  server code
   */
  implicit val timeout = Timeout(2 seconds)
  def getGuitar(query: Query): Future[HttpResponse] = {
    val guitarId = query.get("id").map(_.toInt) // Option[Int]
    guitarId match {
      case None => Future(HttpResponse(StatusCodes.NotFound))
      case Some(id) =>
        val guitarFuture: Future[Option[Guitar]] = (guitarDb ? FindGuitar(id)).mapTo[Option[Guitar]]
        guitarFuture.map{
          case None => HttpResponse(StatusCodes.NotFound)
          case Some(guitar) => HttpResponse(entity = HttpEntity(
            ContentTypes.`application/json`,
            guitar.toJson.prettyPrint
          ))
        }
    }
  }
  val requestHandle: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      val inStock: Option[Boolean] = query.get("inStock").map(_.toBoolean)
      inStock match {
        case None => Future(HttpResponse(StatusCodes.NotFound))
        case Some(inStock) =>
          val guitarsFuture = guitarDb ? (if (inStock) FindGuitarInStock else FindGuitarOutOfStock)
          guitarsFuture.mapTo[List[Guitar]].map { guitars =>
            HttpResponse(entity = HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            ))
          }
      }

    case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      val guitarId = query.get("id").map(_.toInt)
      val quantityOp = query.get("quantity").map(_.toInt)
      val validGuitarResponseFuture: Option[Future[HttpResponse]] = for {
        id <- guitarId
        quantity <- quantityOp
      } yield {
        val newGuitarFuture: Future[Option[Guitar]] = (guitarDb ? AddGuitarQuantity(id, quantity)).mapTo[Option[Guitar]]
        newGuitarFuture.map(_ => HttpResponse(StatusCodes.OK))
      }
      validGuitarResponseFuture.getOrElse(Future(HttpResponse(StatusCodes.BadRequest)))


    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar"), _, _, _) =>
      /*
      query parameter handling code
      localhost:8080/api/endpoint?param1=value1&param2=value2
       */
      val query = uri.query() // query object <=> map
      if (query.isEmpty) {
        val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindAllGuitars).mapTo[List[Guitar]]
        guitarsFuture.map { guitars =>
          HttpResponse(
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            )
          )
        }
      } else {
        // fetch guitar associated to the guitar id
        // localhost:8080/api/guitar?id=45
        getGuitar(query)
      }

    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity, _) =>
      //entities are Source[ByteString]
      val strictEntityFuture = entity.toStrict(3 seconds)
      for { // for-comprehension
        strictEntity <- strictEntityFuture
        guitarJsonString = strictEntity.data.utf8String
        guitar = guitarJsonString.parseJson.convertTo[Guitar]
        guitarCreatedFuture: Future[GuitarCreated] = (guitarDb ? CreateGuitar(guitar)).mapTo[GuitarCreated]
        _ <- guitarCreatedFuture
      } yield HttpResponse(StatusCodes.OK)
      //chain of flatMap and map
//      strictEntityFuture.flatMap { strictEntity =>
//        val guitarJsonString = strictEntity.data.utf8String
//        val guitar = guitarJsonString.parseJson.convertTo[Guitar]
//        val guitarCreatedFuture: Future[GuitarCreated] = (guitarDb ? CreateGuitar(guitar)).mapTo[GuitarCreated]
//        guitarCreatedFuture.map {gc => HttpResponse(StatusCodes.OK)}
//      }

    case request: HttpRequest =>
      request.discardEntityBytes()
      Future {
        HttpResponse(StatusCodes.NotFound)
      }
  }

  /*
  Exercise: enhance the Guitar case class with a quantity field, by default 0
  - GET to
   */
  Http().bindAndHandleAsync(requestHandle, "localhost", 8388)
}
