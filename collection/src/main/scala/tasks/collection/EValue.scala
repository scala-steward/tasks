package tasks.collection

import tasks.queue._
import tasks._
import akka.stream.scaladsl._
import akka.util.ByteString
import scala.concurrent.Future

case class EValue[T](data: SharedFile) extends ResultWithSharedFiles(data) {
  def basename: String =
    data.name

  def get(implicit decoder: Deserializer[T],
          tsc: TaskSystemComponents): Future[T] =
    data.source
      .runFold(ByteString())(_ ++ _)(tsc.actorMaterializer)
      .map(bs => decoder(bs.toArray))(tsc.actorMaterializer.executionContext)

  def source(implicit decoder: Deserializer[T],
             tsc: TaskSystemComponents): Source[T, _] =
    Source.lazilyAsync(() => this.get)

}

object EValue {
  def apply[T](t: T, name: String)(
      implicit encoder: Serializer[T],
      tsc: TaskSystemComponents): Future[EValue[T]] =
    SharedFile
      .apply(Source.single(ByteString(encoder(t))), name: String)
      .map(EValue[T](_))(tsc.actorMaterializer.executionContext)
}