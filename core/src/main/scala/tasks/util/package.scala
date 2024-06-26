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

package tasks

import java.io.{
  PrintWriter,
  BufferedWriter,
  FileWriter,
  FileInputStream,
  FileOutputStream,
  BufferedOutputStream,
  BufferedInputStream,
  StringWriter,
  File
}

import scala.sys.process._
import scala.concurrent.duration._
import scala.util._
import tasks.util.config._
import cats.effect.IO
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

package object util {

  def base64(b: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(b)
  def base64(s: String): Array[Byte] = java.util.Base64.getDecoder.decode(s)

  private def available(port: Int): Boolean = {
    var s: java.net.Socket = null
    try {
      s = new java.net.Socket("localhost", port);

      false
    } catch {
      case _: Exception => true
    } finally {
      if (s != null) {

        s.close

      }
    }
  }

  def chooseNetworkPort(implicit config: TasksConfig): Int =
    Try(config.hostPort)
      .flatMap { p =>
        if (available(p)) Success(p) else Failure(new RuntimeException)
      }
      .getOrElse {

        val s = new java.net.ServerSocket(0);
        val p = s.getLocalPort()
        s.close
        p
      }

  def stackTraceAsString(t: Any): String = {
    if (t.isInstanceOf[Throwable]) {
      val sw = new StringWriter();
      val pw = new PrintWriter(sw);
      t.asInstanceOf[Throwable].printStackTrace(pw);
      sw.toString(); // stack trace as a string
    } else t.toString
  }

  def rethrow[T](
      messageOnError: => String,
      exceptionFactory: (=> String, Throwable) => Throwable
  )(block: => T): T =
    try {
      block
    } catch {
      case e: Throwable => throw (exceptionFactory(messageOnError, e))
    }

  def rethrow[T](messageOnError: => String)(block: => T): T =
    rethrow(messageOnError, new RuntimeException(_, _))(block)

  /** Retry the given block n times. */
  @annotation.tailrec
  def retry[T](n: Int)(fn: => T): Try[T] =
    Try(fn) match {
      case x: Success[T] => x
      case _ if n > 1    => retry(n - 1)(fn)
      case f             => f
    }

  /** Returns the result of the block, and closes the resource.
    *
    * @param param
    *   closeable resource
    * @param f
    *   block using the resource
    */
  def useResource[A <: { def close(): Unit }, B](param: A)(f: A => B): B =
    try {
      f(param)
    } finally {
      import scala.language.reflectiveCalls
      param.close()
    }

  /** Writes text data to file. */
  def writeToFile(fileName: String, data: java.lang.String): Unit =
    useResource(new PrintWriter(new BufferedWriter(new FileWriter(fileName)))) {
      writer =>
        writer.write(data)
    }

  /** Writes text data to file. */
  def writeToFile(file: File, data: String): Unit =
    writeToFile(file.getAbsolutePath, data)

  /** Writes binary data to file. */
  def writeBinaryToFile(fileName: String, data: Array[Byte]): Unit =
    useResource(new BufferedOutputStream(new FileOutputStream(fileName))) {
      writer =>
        writer.write(data)
    }

  /** Writes binary data to file. */
  def writeBinaryToFile(file: File, data: Array[Byte]): Unit =
    writeBinaryToFile(file.getAbsolutePath, data)

  def writeBinaryToFile(data: Array[Byte]): File = {
    val file = File.createTempFile("tmp", "tmp")
    writeBinaryToFile(file.getAbsolutePath, data)
    file
  }

  /** Returns an iterator on the InputStream's data.
    *
    * Closes the stream when read through.
    */
  def readStreamAndClose(is: java.io.InputStream) = new Iterator[Byte] {
    var s = is.read

    def hasNext = s != -1

    def next() = {
      val x = s.toByte; s = is.read;
      if (!hasNext) {
        is.close()
      }; x
    }
  }

  /** Reads file contents into a bytearray. */
  def readBinaryFile(fileName: String): Array[Byte] = {
    useResource(new BufferedInputStream(new FileInputStream(fileName))) { f =>
      readBinaryStream(f)
    }
  }

  /** Reads file contents into a bytearray. */
  def readBinaryFile(f: File): Array[Byte] = readBinaryFile(f.getAbsolutePath)

  /** Reads file contents into a bytearray. */
  def readBinaryStream(f: java.io.InputStream): Array[Byte] = {
    val BufferSize = 8196
    var byteString = akka.util.ByteString()
    val buffer = Array.ofDim[Byte](BufferSize)
    var c = 0
    while (c >= 0) {
      c = f.read(buffer)
      if (c >= 0) {
        val bs = akka.util.ByteString.fromArray(buffer, 0, c)
        byteString ++= bs
      }
    }
    byteString.toArray
  }

  /** Opens a buffered java.io.BufferedOutputStream on the file. Closes it after
    * the block is executed.
    */
  def openFileOutputStream[T](fileName: File, append: Boolean = false)(
      func: BufferedOutputStream => T
  ) =
    useResource(
      new BufferedOutputStream(new FileOutputStream(fileName, append))
    )(func)

  /** Opens a buffered java.io.BufferedInputStream on the file. Closes it after
    * the block is executed.
    */
  def openFileInputStream[T](fileName: File)(func: BufferedInputStream => T) =
    useResource(new BufferedInputStream(new FileInputStream(fileName)))(func)

  /** Execute command with user function to process each line of output.
    *
    * Based on from
    * http://www.jroller.com/thebugslayer/entry/executing_external_system_commands_in
    * Creates 2 new threads: one for the stdout, one for the stderror.
    * @param pb
    *   Description of the executable process
    * @return
    *   Exit code of the process.
    */
  def exec(pb: ProcessBuilder)(stdOutFunc: String => Unit = { (_: String) => })(
      implicit stdErrFunc: String => Unit = (_: String) => ()
  ): Int =
    pb.run(ProcessLogger(stdOutFunc, stdErrFunc)).exitValue()

  /** Execute command. Returns stdout and stderr as strings, and true if it was
    * successful.
    *
    * A process is considered successful if its exit code is 0 and the error
    * stream is empty. The latter criterion can be disabled with the
    * unsuccessfulOnErrorStream parameter.
    * @param pb
    *   The process description.
    * @param unsuccessfulOnErrorStream
    *   if true, then the process is considered as a failure if its stderr is
    *   not empty.
    * @param atMost
    *   max waiting time.
    * @return
    *   (stdout,stderr,success) triples
    */
  def execGetStreamsAndCode(
      pb: ProcessBuilder,
      unsuccessfulOnErrorStream: Boolean = true
  ): (List[String], List[String], Boolean) = {
    var ls: List[String] = Nil
    var lse: List[String] = Nil
    var boolean = true
    val exitvalue = exec(pb) { ln =>
      ls = ln :: ls
    } { ln =>
      if (unsuccessfulOnErrorStream) {
        boolean = false
      }; lse = ln :: lse
    }
    (ls.reverse, lse.reverse, boolean && (exitvalue == 0))
  }

  /** Merge maps with key collision
    * @param fun
    *   Handles key collision
    */
  def addMaps[K, V](a: Map[K, V], b: Map[K, V])(fun: (V, V) => V): Map[K, V] = {
    a ++ b.map { case (key, bval) =>
      val aval = a.get(key)
      val cval = aval match {
        case None    => bval
        case Some(a) => fun((a), (bval))
      }
      (key, cval)
    }
  }

  /** Merge maps with key collision
    * @param fun
    *   Handles key collision
    */
  def addMaps[K, V](a: collection.Map[K, V], b: collection.Map[K, V])(
      fun: (V, V) => V
  ): collection.Map[K, V] = {
    a ++ b.map { case (key, bval) =>
      val aval = a.get(key)
      val cval = aval match {
        case None    => bval
        case Some(a) => fun((a), (bval))
      }
      (key, cval)
    }
  }

  def retryFuture[A](tag: String)(f: => Future[A], c: Int)(implicit
      as: akka.actor.ActorSystem,
      ec: ExecutionContext,
      log: scribe.Logger
  ): Future[A] =
    if (c > 0) f.recoverWith { case e =>
      log.error(e, s"Failed $tag. Retry $c more times.")
      akka.pattern.after(2 seconds, as.scheduler)(
        retryFuture(tag)(
          {
            log.debug(s"Retrying $tag"); f
          },
          c - 1
        )
      )
    }
    else f
  def retryIO[A](tag: String)(f: => IO[A], c: Int)(implicit
      log: scribe.Logger
  ): IO[A] =
    if (c > 0) f.handleErrorWith { case e =>
      IO.delay(log.error(e, s"Failed $tag. Retry $c more times.")) *>
        IO.sleep(2 seconds) *>
        retryIO(tag)(
          IO.delay(log.debug(s"Retrying $tag")) *> f,
          c - 1
        )
    }
    else f

  def reflectivelyInstantiateObject[A](fqcn: String): A = {
    java.lang.Class
      .forName(fqcn)
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[A]
    // import scala.reflect.runtime.universe

    // val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
    // val module = runtimeMirror.staticModule(fqcn)
    // val obj = runtimeMirror.reflectModule(module)

    // obj.instance.asInstanceOf[A]

  }

  def rightOrThrow[A, E](e: Either[E, A]): A = e match {
    case Right(a)           => a
    case Left(e: Throwable) => throw e
    case Left(e)            => throw new RuntimeException(e.toString)
  }

}
