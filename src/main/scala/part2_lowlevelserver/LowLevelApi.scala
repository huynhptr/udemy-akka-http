package part2_lowlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object LowLevelApi extends App {
  implicit val system = ActorSystem("LowLevelServerAPI")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val serverSource = Http().bind("localhost", 8000)
  val connectionSink = Sink.foreach[IncomingConnection] { connection =>
    println(s"Accepted incoming connection from: ${connection.remoteAddress}")
  }
//  val serverBindingFuture = serverSource.to(connectionSink).run()
//  serverBindingFuture.onComplete {
//    case Success(binding) =>
//      println("Server binding successful.")
//      binding.terminate(2 seconds)
//    case Failure(exception) => println(s"Server binding failed: $exception")
//  }

  /*
  Method 1: synchronously serve HTTP response
   */
  val requestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, _, _, _, _) =>
      HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity (
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            | Hello from Akka HTTP!
            | </body>
            |</html>
            |""".stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            | OOPS! The resource can't be found.
            | </body>
            |</html>
            |""".stripMargin
        )
      )
  }
  val httpSyncConnectionHandler = Sink.foreach[IncomingConnection] { connection =>
    connection.handleWithSyncHandler(requestHandler)
  }
//  Http().bind("localhost", 8080).runWith(httpSyncConnectionHandler)

  // shorthand version
//  Http().bindAndHandleSync(requestHandler, "localhost", 8080)

  /*
  Method 2: serve back HTTP response asynchronously
   */
  val asyncRequestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) => // method, URI, HTTP headers, content, protocol (HTTP1.1/HTTP2.0)
      Future(HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity (
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            | Hello from Akka HTTP!
            | </body>
            |</html>
            |""".stripMargin
        )
      ))
    case request: HttpRequest =>
      request.discardEntityBytes()
      Future(HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            | OOPS! The resource can't be found.
            | </body>
            |</html>
            |""".stripMargin
        )
      ))
  }
  val httpAsyncConnectionHandler = Sink.foreach[IncomingConnection] { connection =>
    connection.handleWithAsyncHandler(asyncRequestHandler)
  }
//  Http().bind("localhost", 8081).runWith(httpAsyncConnectionHandler)
//  Http().bindAndHandleAsync(asyncRequestHandler, "localhost", 8081)

  /*
  Method 3: async via Akka streams
   */
  val streamBasedRequestHandler: Flow[HttpRequest, HttpResponse, _] = Flow[HttpRequest].map {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) =>
      HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity (
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            | Hello from Akka HTTP!
            | </body>
            |</html>
            |""".stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            | OOPS! The resource can't be found.
            | </body>
            |</html>
            |""".stripMargin
        )
      )
  }
  //manual version
//  Http().bind("localhost", 8082).runForeach { connection =>
//    connection.handleWith(streamBasedRequestHandler)
//  }
  // shorthand version
//  Http().bindAndHandle(streamBasedRequestHandler, "localhost", 8082)

  /*
  Exercise: create your own HTTP server running on localhost on 8388, which replies
  - with a welcome message on the "front door" localhost:8388
  - with a proper HTML on localhost:8388/about
  - with a 404 message otherwise
   */
  val syncRequestHandler8388: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _) => HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | Welcome to port 8388
          | </body>
          |</html>
          |""".stripMargin
      )
    )
    case HttpRequest(HttpMethods.GET, Uri.Path("/about"), _, _, _) => HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | About port 8388
          | </body>
          |</html>
          |""".stripMargin
      )
    )
    case _ => HttpResponse(StatusCodes.NotFound,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | OOPS! The resource can't be found.
          | </body>
          |</html>
          |""".stripMargin
      ))
  }
//  Http().bindAndHandleSync(syncRequestHandler8388, "localhost", 8388)
  val asyncRequestHandler8388: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _) => Future(HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | Welcome to port 8388
          | </body>
          |</html>
          |""".stripMargin
      )
    ))
    case HttpRequest(HttpMethods.GET, Uri.Path("/about"), _, _, _) => Future(HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | About port 8388
          | </body>
          |</html>
          |""".stripMargin
      )
    ))
    case _ => Future(HttpResponse(StatusCodes.NotFound,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | OOPS! The resource can't be found.
          | </body>
          |</html>
          |""".stripMargin
      )))
  }
//  Http().bindAndHandleAsync(asyncRequestHandler8388, "localhost", 8388)
  val streamBasedRequestHandler8388: Flow[HttpRequest, HttpResponse, _] = Flow[HttpRequest].map {
    case HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _) => HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | Welcome to port 8388
          | </body>
          |</html>
          |""".stripMargin
      )
    )
    case HttpRequest(HttpMethods.GET, Uri.Path("/about"), _, _, _) => HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | About port 8388
          | </body>
          |</html>
          |""".stripMargin
      )
    )
    case HttpRequest(HttpMethods.GET, Uri.Path("/search"), _, _, _) => HttpResponse(
      StatusCodes.Found,
      headers = List(Location("http://google.com"))
    )
    case _ => HttpResponse(StatusCodes.NotFound,
      entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          | OOPS! The resource can't be found.
          | </body>
          |</html>
          |""".stripMargin
      ))
  }
  val bindingFuture = Http().bindAndHandle(streamBasedRequestHandler8388, "localhost", 8388)

  //shutdown the server
  bindingFuture.flatMap(binding => binding.unbind())
    .onComplete(_ => system.terminate())

}
