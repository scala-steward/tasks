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

package tasks.ui

import org.scalatest._

import tasks.circesupport._
import org.scalatest.Matchers
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory

import tasks._

object UIFrontendRun extends TestHelpers {

  val testTask = AsyncTask[Input, Int]("nodeallocationtest", 1) {
    input => implicit computationEnvironment =>
      log.info("Hello from task")
      Thread.sleep(1000)
      Future(1)
  }

  val testConfig2 = {
    val tmp = tasks.util.TempFile.createTempFile(".temp")
    tmp.delete
    ConfigFactory.parseString(
      s"""tasks.fileservice.storageURI=${tmp.getAbsolutePath}
      hosts.numCPU=1      
      tasks.ui.fqcn = default
      """
    )
  }

  def run = {
    withTaskSystem(testConfig2) { implicit ts =>
      import scala.concurrent.ExecutionContext.Implicits.global
      Future.sequence(
        (1 to 100).map(i => testTask(Input(i))(CPUMemoryRequest(1, 500))))

      Thread.sleep(Long.MaxValue)

    }
  }

}

class UIFrontendRunSuite extends FunSuite with Matchers {

  test("keep task system running with ui") {
    UIFrontendRun.run

  }

}
