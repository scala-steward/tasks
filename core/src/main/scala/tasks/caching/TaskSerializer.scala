/*
 * The MIT License
 *
 * Copyright (c) 2015 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland,
 * Group Fellay
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

package tasks.caching

import tasks.queue._
import tasks.fileservice._
import tasks.fileservice.SharedFile._

import io.circe._

trait TaskSerializer {

  def serializeResult(original: UntypedResult): Array[Byte] = {
    val mutableFilesField =
      if (original.mutableFiles.isEmpty) Nil
      else
        List(
          "mutablefiles" -> implicitly[Encoder[Set[SharedFile]]]
            .apply(original.mutableFiles.get))

    val fields = List(
      "files" -> implicitly[Encoder[Set[SharedFile]]].apply(original.files),
      "data" -> Json.fromString(original.data.value)) ++ mutableFilesField

    val js = Json.obj(fields: _*)
    js.noSpaces.getBytes("UTF8")
  }

  def deserializeResult(byteArray: Array[Byte]): UntypedResult = {
    val map = io.circe.parser
      .parse(new String(byteArray, "UTF8"))
      .right
      .get
      .asObject
      .get

    val files =
      implicitly[Decoder[Set[SharedFile]]]
        .decodeJson(map("files").get)
        .right
        .get

    val mutableFiles = map.apply("mutableFiles") match {
      case None => None
      case Some(js) =>
        Some(
          implicitly[Decoder[Set[SharedFile]]]
            .decodeJson(js)
            .right
            .get)
    }

    UntypedResult(files, Base64Data(map("data").get.asString.get), mutableFiles)
  }
}
