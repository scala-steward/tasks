/*
 * The MIT License
 *
 * Copyright (c) 2018 Istvan Bartha
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

package tasks.tracker

import org.scalatest._

import org.scalatest.Matchers

import tasks.circesupport._
import com.typesafe.config.ConfigFactory
import scala.concurrent._
import scala.concurrent.duration._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import tasks._
import tasks.fileservice.HistoryContextImpl
object QueryLogTest extends TestHelpers {

  val task1 = AsyncTask[Input, Int]("task1", 1) {
    input => implicit computationEnvironment =>
      releaseResources
      for {
        files <- Future.traverse(0 to input.i) { i =>
          SharedFile(Source.single(ByteString("abcd")), i.toString)
        }
        _ <- scatter(InputSF(files.toList))(ResourceRequest(1, 500))
      } yield {
        log.info("ALL DONE")
        1
      }
  }

  val gather = AsyncTask[InputSF, Int]("gather", 1) {
    _ => implicit computationEnvironment =>
      log.info("gather")
      Thread.sleep(1000)
      Future(1)
  }
  val work = AsyncTask[SharedFile, SharedFile]("work", 1) {
    input => implicit computationEnvironment =>
      log.info("work " + input.name)
      audit("work " + input.name)
      Thread.sleep(500)
      SharedFile(Source.single(ByteString("abcd")), input.name + ".worked")
  }

  val scatter = AsyncTask[InputSF, Int]("scatter", 1) {
    input => implicit computationEnvironment =>
      releaseResources
      log.info("scatter")
      for {
        scatteredFiles <- Future.traverse(input.files1) { i =>
          work(i)(ResourceRequest(1, 500))
        }
        gathered <- gather(InputSF(scatteredFiles))(ResourceRequest(1, 500))

      } yield gathered

  }

  val file = tasks.util.TempFile.createTempFile(".json")

  val testConfig2 = {
    val tmp = tasks.util.TempFile.createTempFile(".temp")
    tmp.delete
    ConfigFactory.parseString(
      s"""tasks.fileservice.storageURI=${tmp.getAbsolutePath}
      hosts.numCPU=1      
      tasks.tracker.fqcn = default
      tasks.tracker.logFile = ${file.getAbsolutePath}
      """
    )
  }

  def run = {
    withTaskSystem(testConfig2) { implicit ts =>
      import scala.concurrent.ExecutionContext.Implicits.global

      val future = for {
        t1 <- task1(Input(1))(ResourceRequest(1, 500))
      } yield t1

      Await.result(future, atMost = 30 seconds)

    }
  }

}

class QueryLogTestSuite extends FunSuite with Matchers {

  test("should query log") {
    QueryLogTest.run.get
    Thread.sleep(1000)
    QueryLogTest.file.canRead shouldBe true
    println(QueryLogTest.file)
    val nodes = tasks.util.openFileInputStream(QueryLogTest.file) {
      inputStream =>
        val nodes = QueryLog.readNodes(inputStream,
                                       excludeTaskIds = Set.empty,
                                       includeTaskIds = Set.empty)
        nodes.size shouldBe 5

        QueryLog.trees(nodes).size shouldBe 1

        val runtimes = QueryLog.computeRuntimes(nodes, subtree = None)

        val root = runtimes.find(_.taskId == "task1").get
        root.cpuNeed.get shouldBe 2
        root.cpuTime.get > 2.0 shouldBe true
        root.wallClockTime.get > 1.5 shouldBe true

        println(QueryLog.plotTimes(runtimes, seconds = true))
        nodes
    }
    tasks.util.openFileInputStream(QueryLogTest.file) { inputStream =>
      val workerLogs = scala.io.Source
        .fromInputStream(inputStream)
        .getLines
        .map { line =>
          io.circe.parser.decode[ResourceUtilizationRecord](line).right.get
        }
        .filter(_.taskId.id == "work")
        .toList

      workerLogs.size shouldBe 2

      workerLogs.foreach { record =>
        record.metadata.get.logs.nonEmpty shouldBe true
        val traceId = record.metadata.get.dependencies.head.context.get
          .asInstanceOf[HistoryContextImpl]
          .traceId
          .get
        val task = nodes.filter(_.id == traceId)
        task.size shouldBe 1
        task.head.taskId shouldBe "task1"
      }

    }

  }

}
