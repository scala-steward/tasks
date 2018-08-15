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

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

case class Base64Data(value: String) {
  def bytes: Array[Byte] = base64(value)
}
object Base64Data {
  implicit val encoder: Encoder[Base64Data] = deriveEncoder[Base64Data]
  implicit val decoder: Decoder[Base64Data] = deriveDecoder[Base64Data]
  def apply(b: Array[Byte]): Base64Data = Base64Data(base64(b))
}

case class TaskDescription(taskId: TaskId,
                           startData: Base64Data,
                           persistent: Option[Base64Data])

object TaskDescription {
  implicit val encoder: Encoder[TaskDescription] =
    deriveEncoder[TaskDescription]
  implicit val dec: Decoder[TaskDescription] =
    deriveDecoder[TaskDescription]
}

case class ScheduleTask(
    description: TaskDescription,
    taskImplementation: String,
    resource: VersionedCPUMemoryRequest,
    balancerActor: ActorRef,
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

class TaskLauncher(
    taskQueue: ActorRef,
    nodeLocalCache: ActorRef,
    slots: VersionedCPUMemoryAvailable,
    refreshRate: FiniteDuration,
    auxExecutionContext: ExecutionContext,
    actorMaterializer: Materializer,
    remoteStorage: RemoteFileStorage,
    managedStorage: Option[ManagedFileStorage]
)(implicit config: TasksConfig)
    extends Actor
    with akka.actor.ActorLogging {

  private case object CheckQueue extends Serializable

  val maxResources: VersionedCPUMemoryAvailable = slots
  var availableResources: VersionedCPUMemoryAvailable = maxResources

  def idle = maxResources == availableResources
  var idleState: Long = 0L

  var denyWorkBeforeShutdown = false

  var startedTasks
    : List[(ActorRef, ScheduleTask, VersionedCPUMemoryAllocated)] = Nil

  var freed = Set[ActorRef]()

  def launch(sch: ScheduleTask, proxies: List[ActorRef]) = {

    log.debug("Launch method")

    val allocatedResource = availableResources.maximum(sch.resource)
    availableResources = availableResources.substract(allocatedResource)

    val actor = context.actorOf(
      Props(
        classOf[Task],
        Class
          .forName(sch.taskImplementation)
          .asInstanceOf[java.lang.Class[_]]
          .getConstructor()
          .newInstance(),
        self,
        sch.balancerActor,
        FileServiceActor(sch.fileServiceActor, managedStorage, remoteStorage),
        sch.cacheActor,
        nodeLocalCache,
        allocatedResource.cpuMemoryAllocated,
        sch.fileServicePrefix.append(sch.description.taskId.id),
        auxExecutionContext,
        actorMaterializer,
        config
      ).withDispatcher("task-worker-dispatcher")
    )
    log.debug("Actor constructed")
    // log.debug( "Actor started")
    proxies.foreach { sender =>
      actor ! RegisterForNotification(sender)
    }

    startedTasks = (actor, sch, allocatedResource) :: startedTasks

    log.debug("Sending startdata to actor.")
    actor ! sch.description.startData
    log.debug("startdata sent.")
    allocatedResource
  }

  def askForWork(): Unit = {
    if (!availableResources.empty && !denyWorkBeforeShutdown) {
      // log.debug("send askForWork" + availableResources)
      taskQueue ! AskForWork(availableResources)
    }
  }

  var scheduler: Cancellable = null

  override def preStart {
    log.debug("TaskLauncher starting")

    import context.dispatcher

    scheduler = context.system.scheduler.schedule(
      initialDelay = 0 seconds,
      interval = refreshRate,
      receiver = self,
      message = CheckQueue
    )

  }

  override def postStop {
    scheduler.cancel

    startedTasks.foreach(x => x._1 ! PoisonPill)
    log.info(
      s"TaskLauncher stopped, sent PoisonPill to ${startedTasks.size} running tasks.")
  }

  def taskFinished(taskActor: ActorRef, receivedResult: UntypedResult) {
    val elem = startedTasks
      .find(_._1 == taskActor)
      .getOrElse(throw new RuntimeException(
        "Wrong message received. No such taskActor."))
    val sch = elem._2
    import akka.pattern.ask
    import context.dispatcher
    (
      sch.cacheActor
        .?(
          SaveResult(sch.description,
                     receivedResult,
                     sch.fileServicePrefix.append(sch.description.taskId.id)))(
          sender = taskActor,
          timeout = 5 seconds
        )
      )
      .foreach { _ =>
        taskQueue ! TaskDone(sch, receivedResult)
      }

    startedTasks = startedTasks.filterNot(_ == elem)
    if (!freed.contains(taskActor)) {
      availableResources = availableResources.addBack(elem._3)
    } else {
      freed -= taskActor
    }
  }

  def taskFailed(taskActor: ActorRef, cause: Throwable) {

    val elem = startedTasks
      .find(_._1 == taskActor)
      .getOrElse(throw new RuntimeException(
        "Wrong message received. No such taskActor."))
    val sch = elem._2

    startedTasks = startedTasks.filterNot(_ == elem)

    if (!freed.contains(taskActor)) {
      availableResources = availableResources.addBack(elem._3)
    } else {
      freed -= taskActor
    }

    taskQueue ! TaskFailedMessageToQueue(sch, cause)

  }

  def receive = {
    case ScheduleWithProxy(sch, acs) =>
      log.debug(s"Received ScheduleWithProxy from $acs")
      if (!denyWorkBeforeShutdown) {

        if (idle) {
          idleState += 1
        }
        val allocated = launch(sch, acs)
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
      if (idle) {
        denyWorkBeforeShutdown = true
        sender ! ReadyForShutdown
      }

    case WhatAreYouDoing =>
      log.debug(s"Received WhatAreYouDoing. idle:$idle, idleState:$idleState")
      if (idle) {
        sender ! Idling(idleState)
      } else {
        sender ! Working
      }

    case Release =>
      val taskActor = sender
      val allocated = startedTasks.find(_._1 == taskActor).map(_._3)
      if (allocated.isEmpty) log.error("Can't find actor ")
      else {
        availableResources = availableResources.addBack(allocated.get)
        freed = freed + taskActor
      }
      askForWork

    case x => log.debug("unhandled" + x)

  }

}
