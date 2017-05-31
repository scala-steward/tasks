/*
 * The MIT License
 *
 * Copyright (c) 2015 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland,
 * Group Fellay
 * Modified work, Copyright (c) 2016 Istvan Bartha

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

package tasks

import tasks.queue._
import tasks.shared._
import tasks.fileservice._

import akka.actor._

import scala.concurrent._

import io.circe.{Encoder, Decoder}

abstract class ResultWithSharedFiles(sf: SharedFile*) extends Product {
  def files = sf
}

// This is the prerequisitives of a task
trait HasPersistent[+A] extends Serializable { self: A =>
  def persistent: A
}

class TaskDefinition[A: Serializer, B: Deserializer](val computation: CompFun2,
                                                     val taskId: TaskId) {

  def apply(a: A)(resource: CPUMemoryRequest)(
      implicit components: TaskSystemComponents): Future[B] =
    tasks.queue
      .newTask[B, A](a, resource, computation, taskId)
      .?(components.actorsystem.dispatcher)

}
