/*
 * The MIT License
 *
 * Copyright (c) 2017 Istvan Bartha
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package tasks.fileservice

import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.util._
import com.bluelabs.s3stream._
import scala.concurrent.{ExecutionContext, Future}

class StreamHelper(implicit as: ActorSystem,
                   actorMaterializer: ActorMaterializer,
                   ec: ExecutionContext) {

  val s3stream =
    new com.bluelabs.s3stream.S3Stream(tasks.util.S3Helpers.credentials)

  def s3Loc(uri: Uri) = {
    val bucket = uri.authority.host.toString
    val key = uri.path.toString.drop(1)
    S3Location(bucket, key)
  }

  def createSourceHttp(uri: Uri): Source[ByteString, _] =
    Source
      .fromFuture(
          Http().singleRequest(HttpRequest(uri = uri)).map(_.entity.dataBytes))
      .flatMapConcat(identity)

  def createSourceS3(uri: Uri): Source[ByteString, _] =
    s3stream.download(s3Loc(uri))

  def createSource(uri: Uri) = uri.scheme match {
    case "http" | "https" => createSourceHttp(uri)
    case "s3" => createSourceS3(uri)
  }

  def getContentLengthHttp(uri: Uri): Future[Long] =
    Http()
      .singleRequest(HttpRequest(uri = uri))
      .map(_.header[headers.`Content-Length`].map(_.length).get)

  def getContentLengthS3(uri: Uri): Future[Long] =
    s3stream.getMetadata(s3Loc(uri)).map(_.contentLength.get)

  def getContentLength(uri: Uri): Future[Long] = uri.scheme match {
    case "http" | "https" => getContentLengthHttp(uri)
    case "s3" => getContentLengthS3(uri)
  }

  def getETagHttp(uri: Uri): Future[String] =
    Http()
      .singleRequest(HttpRequest(uri = uri))
      .map(_.header[headers.`ETag`].map(_.value).get)

  def getETagS3(uri: Uri): Future[String] =
    s3stream.getMetadata(s3Loc(uri)).map(_.eTag.get)

  def getETag(uri: Uri): Future[String] = uri.scheme match {
    case "http" | "https" => getETagHttp(uri)
    case "s3" => getETagS3(uri)
  }

}
