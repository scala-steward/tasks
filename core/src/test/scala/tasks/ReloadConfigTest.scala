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

package tasks

import org.scalatest.funsuite.{AnyFunSuite => FunSuite}
import cats.effect.unsafe.implicits.global

import org.scalatest.matchers.should.Matchers

import tasks.jsonitersupport._
import org.ekrich.config.ConfigFactory
import cats.effect.IO

object ReloadConfigTest extends TestHelpers with Matchers {

  val testTask = Task[Input, Int]("nodeallocationtest", 1) {
    input => implicit computationEnvironment =>
      scribe.info("Hello from task")
      if (input.i > 1) {
        computationEnvironment.components.tasksConfig.trackerFqcn shouldBe "Input(1)"
      }
      System.getProperties.setProperty("tasks.tracker.fqcn", input.toString)
      Thread.sleep(2100)
      IO(1)
  }

  val testConfig2 = {
    val tmp = tasks.util.TempFile.createTempFile(".temp")
    tmp.delete
    ConfigFactory.parseString(
      s"""tasks.fileservice.storageURI=${tmp.getAbsolutePath}
      hosts.numCPU=1
      tasks.addShutdownHook = false
      tasks.failuredetector.acceptable-heartbeat-pause = 10 s
      tasks.maxConfigLoadInterval = 2 seconds
      
      """
    )
  }

  def run = {
    withTaskSystem(testConfig2) { implicit ts =>
      val f1 = testTask(Input(1))(ResourceRequest(1, 500))

      val f2 = f1.flatMap(_ => testTask(Input(2))(ResourceRequest(1, 500)))
      val future = for {
        t1 <- f1
        t2 <- f2
      } yield t1 + t2

      (future)

    }
  }

}

class ReloadConfigTestSuite extends FunSuite with Matchers {

  ignore("should reload configuration") {
    ReloadConfigTest.run.unsafeRunSync().toOption.get should equal(2)

  }

}
