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

import org.scalatest._
import scala.concurrent.duration._
import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import akka.actor.{Actor, PoisonPill, ActorRef, Props, ActorSystem}
import com.typesafe.config.ConfigFactory

import java.io._
import tasks.kvstore._

import org.scalatest.FunSpec
import org.scalatest.Matchers

import tasks.queue._
import tasks.caching._
import tasks.caching.kvstore._
import tasks.fileservice._
import tasks.util._
import tasks.shared._
import tasks.simpletask._

object Conf {
  val str = """my-pinned-dispatcher {
  executor = "thread-pool-executor"
  type = PinnedDispatcher
  thread-pool-executor.allow-core-timeout=off
}
akka.loglevel = "DEBUG" """
}

class FileServiceSpec
    extends TestKit(
        ActorSystem("testsystem",
                    ConfigFactory
                      .parseString(Conf.str)
                      .withFallback(ConfigFactory.load("akkaoverrides.conf"))))
    with ImplicitSender
    with FunSpecLike
    with Matchers
    with BeforeAndAfterAll {
  self: Suite =>

  override def afterAll {
    Thread.sleep(1500)
    system.shutdown

  }

  implicit val prefix = FileServicePrefix(Vector())

  describe("fileservice new file folderstorage ") {
    it("add new file") {
      val data = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7)
      val input = TempFile.createTempFile(".in")
      writeBinaryToFile(input.getCanonicalPath, data)

      val folder =
        new File(new java.io.File(getClass.getResource("/").getPath), "test1")
      folder.mkdir
      val folder2 =
        new File(new java.io.File(getClass.getResource("/").getPath), "test1f")
      folder2.mkdir
      val service = system.actorOf(
          Props(new FileService(
                  new FolderFileStorage(folder, false)
              )))
      implicit val serviceimpl = FileServiceActor(service)
      val t = SharedFile(input, "proba")

      readBinaryFile(new java.io.File(folder, "proba").getCanonicalPath).deep should equal(
          data.deep)
    }

    it("add new file and ask for it") {
      val data = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7)
      val input = TempFile.createTempFile(".in")
      writeBinaryToFile(input.getCanonicalPath, data)

      val folder =
        new File(new java.io.File(getClass.getResource("/").getPath), "test2")
      folder.mkdir
      val folder2 =
        new File(new java.io.File(getClass.getResource("/").getPath), "test2f")
      folder2.mkdir
      val service = system.actorOf(
          Props(new FileService(
                  new FolderFileStorage(folder, false)
              )))
      implicit val serviceimpl = FileServiceActor(service)
      implicit val nlc =
        NodeLocalCacheActor(system.actorOf(Props[NodeLocalCache]))
      val t: SharedFile = SharedFile(input, "proba")

      readBinaryFile(new java.io.File(folder, "proba").getCanonicalPath).deep should equal(
          data.deep)

      val path = SharedFileHelper.getPathToFile(t)
      readBinaryFile(path.getCanonicalPath).deep should equal(data.deep)

    }

    it("add new file and ask for streaming") {
      val data = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7)
      val input = TempFile.createTempFile(".in")
      writeBinaryToFile(input.getCanonicalPath, data)

      val folder =
        new File(new java.io.File(getClass.getResource("/").getPath), "test2")
      folder.mkdir
      val folder2 =
        new File(new java.io.File(getClass.getResource("/").getPath), "test4f")
      folder2.mkdir
      val service = system.actorOf(
          Props(new FileService(new FolderFileStorage(folder, false))))
      implicit val serviceimpl = FileServiceActor(service)
      val t: SharedFile = SharedFile(input, "proba")

      readBinaryFile(new java.io.File(folder, "proba").getCanonicalPath).deep should equal(
          data.deep)

      SharedFileHelper.openStreamToFile(t) { inputstream =>
        readBinaryStream(inputstream).deep should equal(data.deep)
      }

    }

    it("after cache restart") {
      val data = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7)

      val folder =
        new File(new java.io.File(getClass.getResource("/").getPath), "test3")
      folder.mkdir
      val folder2 =
        new File(new java.io.File(getClass.getResource("/").getPath), "test3f")
      folder2.mkdir
      val input = new java.io.File(folder, "proba")
      writeBinaryToFile(input.getCanonicalPath, data)
      val service = system.actorOf(
          Props(new FileService(new FolderFileStorage(folder, false))))
      implicit val serviceimpl = FileServiceActor(service)
      implicit val nlc =
        NodeLocalCacheActor(system.actorOf(Props[NodeLocalCache]))

      val t: SharedFile = SharedFileHelper.createForTesting(
          "proba",
          16,
          com.google.common.hash.Hashing.crc32c.hashBytes(data).asInt)

      val path = SharedFileHelper.getPathToFile(t)
      readBinaryFile(path.getCanonicalPath).deep should equal(data.deep)

      t.isAccessible should be(true)

    }
  }

  describe("fileservice with centralized storage with simulated remote") {

    it("after cache restart") {
      val data = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7)

      val folder =
        new File(new java.io.File(getClass.getResource("/").getPath), "test9")
      folder.mkdir
      val folder2 =
        new File(new java.io.File(getClass.getResource("/").getPath), "test9f")
      folder2.mkdir
      val input = new java.io.File(folder, "proba")
      writeBinaryToFile(input.getCanonicalPath, data)
      val service = system.actorOf(
          Props(new FileService(new FolderFileStorage(folder, true),
                                8,
                                (_: File) => false)))
      implicit val serviceimpl = FileServiceActor(service)
      implicit val nlc =
        NodeLocalCacheActor(system.actorOf(Props[NodeLocalCache]))

      val t: SharedFile = SharedFileHelper.createForTesting(
          "proba",
          16,
          com.google.common.hash.Hashing.crc32c.hashBytes(data).asInt)

      val path = SharedFileHelper.getPathToFile(t)
      readBinaryFile(path.getCanonicalPath).deep should equal(data.deep)

    }
  }

}
