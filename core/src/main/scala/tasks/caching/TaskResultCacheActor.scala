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

package tasks.caching

import akka.actor.{Actor, PoisonPill, ActorLogging, Stash}
import akka.pattern.pipe
import scala.concurrent.Future

import scala.util._

import tasks.util.config._
import tasks.fileservice._
import tasks.wire._
import tasks.queue.TaskId

class TaskResultCacheActor(
    val cacheMap: Cache,
    fileService: FileServiceComponent)(implicit config: TasksConfig)
    extends Actor
    with ActorLogging
    with Stash {

  implicit def FS: FileServiceComponent = fileService

  import context.dispatcher

  private case object SetDone

  override def preStart = {
    log.info(
      "Cache service starting. " + cacheMap.toString + s". config.verifySharedFileInCache: ${config.verifySharedFileInCache}. config.skipContentHashVerificationAfterCache: ${config.skipContentHashVerificationAfterCache}.")
  }

  override def postStop = {
    cacheMap.shutDown
    log.info("TaskResultCacheActor stopped.")
  }

  private def waitUntilSavingIsDone(taskId: TaskId): Receive = {
    case SetDone =>
      log.debug("Save done " + taskId)
      unstashAll()
      context.unbecome()
    case _ => stash()
  }

  def receive = {
    case PoisonPillToCacheActor => self ! PoisonPill
    case SaveResult(description, result, prefix) =>
      log.debug("SavingResult")
      context.become(waitUntilSavingIsDone(description.taskId),
                     discardOld = false)
      cacheMap
        .set(description, result)(prefix)
        .map(_ => SetDone)
        .recover {
          case e =>
            log.error(e, "Error while saving into cache")
            SetDone
        }
        .pipeTo(self)
      sender ! true

    case CheckResult(scheduleTask, originalSender) =>
      val savedSender = sender
      val taskId = scheduleTask.description.taskId
      val queryFileServicePrefix =
        scheduleTask.fileServicePrefix.append(taskId.id)

      def lookup =
        cacheMap
          .get(scheduleTask.description)(queryFileServicePrefix)
          .recover {
            case e =>
              log.error(e, "Error while looking up in cache")
              None
          }

      val answer = for {
        cacheLookup <- lookup
        answer <- cacheLookup match {
          case None =>
            log.debug(s"Checking: $taskId. Not found in cache.")
            Future.successful(
              AnswerFromCache(Left("TaskNotFoundInCache"),
                              originalSender,
                              scheduleTask))
          case _ if !config.verifySharedFileInCache =>
            log.debug(s"Checking: $taskId. Got something (not verified).")
            Future.successful(
              AnswerFromCache(Right(cacheLookup), originalSender, scheduleTask))
          case Some(cacheLookup) =>
            forallFutures(cacheLookup.files.map(SharedFileHelper.isAccessible))
              .recover {
                case e =>
                  log.warning(
                    s"Checking: $taskId. Got something ($cacheLookup), but failed to verify after cache with error: $e.")
                  false
              }
              .map {
                case false =>
                  log.warning(
                    s"Checking: $taskId. Got something ($cacheLookup), but failed to verify after cache.")
                  AnswerFromCache(Left("TaskNotFoundInCache"),
                                  originalSender,
                                  scheduleTask)
                case true =>
                  log.debug(s"Checking: $taskId. Got something (verified).")
                  AnswerFromCache(Right(Some(cacheLookup)),
                                  originalSender,
                                  scheduleTask)

              }
        }
      } yield answer
      answer.pipeTo(savedSender)

  }

  private def forallFutures(futures: Set[Future[Boolean]]) =
    Future.sequence(futures).map(_.forall(identity))
}