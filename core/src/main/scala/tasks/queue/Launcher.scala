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

package tasks.queue

import akka.actor.{
  Actor,
  PoisonPill,
  ActorRef,
  Props,
  Cancellable,
  ExtendedActorSystem
}
import akka.stream.Materializer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.lang.Class

import tasks.util._
import tasks.util.config._
import tasks.shared._
import tasks.fileservice._
import tasks.wire._

import io.circe.{Encoder}
import io.circe.generic.semiauto._

object Base64DataHelpers {
  def toBytes(b64: Base64Data): Array[Byte] = base64(b64.value)
  def apply(b: Array[Byte]): Base64Data = Base64Data(base64(b))
}

case class ScheduleTask(
    description: TaskDescription,
    taskImplementation: String,
    resource: VersionedCPUMemoryRequest,
    queueActor: ActorRef,
    fileServiceActor: ActorRef,
    fileServicePrefix: FileServicePrefix,
    cacheActor: ActorRef
)

object ScheduleTask {
  implicit val encoder: Encoder[ScheduleTask] = deriveEncoder[ScheduleTask]
  implicit def decoder(implicit as: ExtendedActorSystem) = {
    val _ = as
    deriveDecoder[ScheduleTask]
  }
}

class Launcher(
    queueActor: ActorRef,
    nodeLocalCache: ActorRef,
    slots: VersionedCPUMemoryAvailable,
    refreshInterval: FiniteDuration,
    auxExecutionContext: ExecutionContext,
    actorMaterializer: Materializer,
    remoteStorage: RemoteFileStorage,
    managedStorage: Option[ManagedFileStorage]
)(implicit config: TasksConfig)
    extends Actor
    with akka.actor.ActorLogging {

  private case object CheckQueue extends Serializable

  private val maxResources: VersionedCPUMemoryAvailable = slots
  private var availableResources: VersionedCPUMemoryAvailable = maxResources

  private def isIdle = maxResources == availableResources
  private var idleState: Long = 0L

  private var denyWorkBeforeShutdown = false

  private var runningTasks
    : List[(ActorRef, ScheduleTask, VersionedCPUMemoryAllocated)] = Nil

  private var freed = Set[ActorRef]()

  private def launch(scheduleTask: ScheduleTask, proxies: List[Proxy]) = {

    log.debug("Launch method")

    val allocatedResource = availableResources.maximum(scheduleTask.resource)
    availableResources = availableResources.substract(allocatedResource)

    val taskActor = context.actorOf(
      Props(
        classOf[Task],
        Class
          .forName(scheduleTask.taskImplementation)
          .asInstanceOf[java.lang.Class[_]]
          .getConstructor()
          .newInstance(),
        self,
        scheduleTask.queueActor,
        FileServiceComponent(scheduleTask.fileServiceActor,
                             managedStorage,
                             remoteStorage),
        scheduleTask.cacheActor,
        nodeLocalCache,
        allocatedResource.cpuMemoryAllocated,
        scheduleTask.fileServicePrefix.append(
          scheduleTask.description.taskId.id),
        auxExecutionContext,
        actorMaterializer,
        config
      ).withDispatcher("task-worker-dispatcher")
    )
    log.debug("Actor constructed")
    proxies.foreach {
      case Proxy(proxyActorRef) =>
        taskActor ! RegisterForNotification(proxyActorRef)
    }

    runningTasks = (taskActor, scheduleTask, allocatedResource) :: runningTasks

    log.debug("Sending input to actor.")
    taskActor ! scheduleTask.description.input
    log.debug("startdata sent.")
    allocatedResource
  }

  private def askForWork(): Unit = {
    if (!availableResources.empty && !denyWorkBeforeShutdown) {
      queueActor ! AskForWork(availableResources)
    }
  }

  private var scheduler: Cancellable = null

  override def preStart: Unit = {
    log.debug("TaskLauncher starting")

    import context.dispatcher

    scheduler = context.system.scheduler.schedule(
      initialDelay = 0 seconds,
      interval = refreshInterval,
      receiver = self,
      message = CheckQueue
    )

  }

  override def postStop: Unit = {
    scheduler.cancel

    runningTasks.foreach(_._1 ! PoisonPill)
    log.info(
      s"TaskLauncher stopped, sent PoisonPill to ${runningTasks.size} running tasks.")
  }

  private def taskFinished(taskActor: ActorRef,
                           receivedResult: UntypedResult): Unit = {
    val elem = runningTasks
      .find(_._1 == taskActor)
      .getOrElse(throw new RuntimeException(
        "Wrong message received. No such taskActor."))
    val scheduleTask = elem._2
    import akka.pattern.ask
    import context.dispatcher
    (
      scheduleTask.cacheActor
        .?(
          SaveResult(scheduleTask.description,
                     receivedResult,
                     scheduleTask.fileServicePrefix.append(
                       scheduleTask.description.taskId.id)))(
          timeout = 5 seconds
        )
      )
      .foreach { _ =>
        queueActor ! TaskDone(scheduleTask, receivedResult)
      }

    runningTasks = runningTasks.filterNot(_ == elem)
    if (!freed.contains(taskActor)) {
      availableResources = availableResources.addBack(elem._3)
    } else {
      freed -= taskActor
    }
  }

  private def taskFailed(taskActor: ActorRef, cause: Throwable): Unit = {

    val elem = runningTasks
      .find(_._1 == taskActor)
      .getOrElse(throw new RuntimeException(
        "Wrong message received. No such taskActor."))
    val sch = elem._2

    runningTasks = runningTasks.filterNot(_ == elem)

    if (!freed.contains(taskActor)) {
      availableResources = availableResources.addBack(elem._3)
    } else {
      freed -= taskActor
    }

    queueActor ! TaskFailedMessageToQueue(sch, cause)

  }

  def receive = {
    case ScheduleWithProxy(scheduleTask, proxies) =>
      log.debug(s"Received ScheduleWithProxy from $proxies")
      if (!denyWorkBeforeShutdown) {

        if (isIdle) {
          idleState += 1
        }
        val allocated = launch(scheduleTask, proxies)
        sender ! Ack(allocated)
        askForWork
      }

    case InternalMessageFromTask(actor, result) =>
      taskFinished(actor, result);
      askForWork

    case InternalMessageTaskFailed(actor, cause) =>
      taskFailed(actor, cause)
      askForWork

    case CheckQueue => askForWork
    case Ping       => sender ! Pong
    case PrepareForShutdown =>
      if (isIdle) {
        denyWorkBeforeShutdown = true
        sender ! ReadyForShutdown
      }

    case WhatAreYouDoing =>
      val idle = isIdle
      log.debug(s"Received WhatAreYouDoing. idle:$idle, idleState:$idleState")
      if (idle) {
        sender ! Idling(idleState)
      } else {
        sender ! Working
      }

    case Release =>
      val taskActor = sender
      val allocated = runningTasks.find(_._1 == taskActor).map(_._3)
      if (allocated.isEmpty) log.error("Can't find actor ")
      else {
        availableResources = availableResources.addBack(allocated.get)
        freed = freed + taskActor
      }
      askForWork

    case other => log.debug("unhandled" + other)

  }

}