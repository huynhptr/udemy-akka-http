package part1_recap

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}

import scala.util.{Failure, Success}

object AkkaStreamRecap extends App {
  implicit val system = ActorSystem("AkkaStreamRecap")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val source = Source(1 to 100)
  val sink = Sink.foreach[Int](println)
  val flow = Flow[Int].map(x => x + 1)

  val runnableGraph = source.via(flow).to(sink)
  val simpleMaterializedValue = runnableGraph
//    .run() // materialization

  // materialized value
  val sumSink = Sink.fold[Int, Int](0)((acc, ele) => acc + ele)
  val sumFuture = source.runWith(sumSink)

  sumFuture.onComplete {
    case Success(value) => println(s"The sum is: $value")
    case Failure(exception) => println(s"Summing all the numbers from the simple source FAILED: $exception")
  }

  val anotherMaterializedValue = source.viaMat(flow)(Keep.right).toMat(sink)(Keep.left).run()
  /*
  1 - materializing a graph means materializing ALL the components
  2 - a materialized value can be ANYTHING AT ALL
   */

  /* Backpressure actions
  - buffer element
  - apply a strategy in case the buffer overflow
  - fail the entire stream
   */
  val bufferedFlow = Flow[Int].buffer(10, OverflowStrategy.dropHead)
  source.async
    .via(bufferedFlow).async
    .runForeach{ e =>
      // a slow consumer
      Thread.sleep(100)
      println(e)
    }
}
