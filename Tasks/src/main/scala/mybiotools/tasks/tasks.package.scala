/*
* The MIT License
*
* Copyright (c) 2015 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland,
* Group Fellay
* Copyright (c) 2016 Istvan Bartha
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

import java.io.File
import akka.actor.{ ActorRef, ActorSystem }
import scala.concurrent.Future
import akka.event.LogSource
import java.util.concurrent.TimeUnit.{ MILLISECONDS, NANOSECONDS, SECONDS }

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import scala.collection.JavaConversions._
import scala.util.Try

import akka.actor.{ Props, ActorRefFactory, ActorContext }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.duration._
import scala.concurrent._
import akka.pattern.ask

import tasks.caching._
import tasks.queue._
import tasks.fileservice._
import tasks.util._
import tasks.deploy._
import tasks.shared._
import tasks.elastic.ec2._
import tasks.elastic.drmaa._
import tasks.elastic.lsf._
import tasks.elastic.ssh._

package object tasks {

  def createLogger(s: AnyRef)(implicit component: TaskSystemComponents): akka.event.LoggingAdapter = component.getLogger(s)

  implicit def tsc(implicit ts: TaskSystem): TaskSystemComponents = ts.components

  implicit def fs(implicit component: TaskSystemComponents): FileServiceActor = component.fs

  implicit def ts(implicit component: ComputationEnvironment): TaskSystemComponents = component.components

  implicit def launcherActor(implicit component: ComputationEnvironment): LauncherActor = component.launcher

  implicit def resourceAllocated(implicit component: ComputationEnvironment) = component.resourceAllocated

  implicit def log(implicit component: ComputationEnvironment) = component.log

  implicit def actorsystem(implicit component: TaskSystemComponents): ActorSystem = component.actorsystem

  implicit def filePrefix(implicit component: TaskSystemComponents): FileServicePrefix = component.filePrefix

  implicit def nodeLocalCache(implicit component: TaskSystemComponents): NodeLocalCacheActor = component.nodeLocalCache

  implicit def queueActor(implicit component: TaskSystemComponents): QueueActor = component.queue

  implicit def cacheActor(implicit component: TaskSystemComponents): CacheActor = component.cache

  def ls(pattern: String)(implicit component: TaskSystemComponents): List[SharedFile] = {
    implicit val timout = akka.util.Timeout(1441 minutes)
    Await.result(fs.actor ? GetListOfFilesInStorage(pattern), atMost = scala.concurrent.duration.Duration.Inf).asInstanceOf[List[SharedFile]]
  }

  def remoteCacheAddress(implicit t: QueueActor, s: ActorContext): String = if (t.actor.path.address.host.isDefined && t.actor.path.address.port.isDefined)
    t.actor.path.address.host.get + ":" + t.actor.path.address.port.get
  else s.system.settings.config.getString("akka.remote.netty.tcp.hostname") + ":" + s.system.settings.config.getString("akka.remote.netty.tcp.port")

  def withTaskSystem[T](f: TaskSystemComponents => T): T = {
    val ts = defaultTaskSystem
    val r = try {
      f(ts.components)
    } finally {
      ts.shutdown
    }

    r
  }

  def defaultTaskSystem: TaskSystem = defaultTaskSystem(config.global, None)
  def defaultTaskSystem(string: String): TaskSystem = defaultTaskSystem(config.global, Some(ConfigFactory.parseString(string)))
  def defaultTaskSystem(defaultConf: Config, extraConf: Option[Config]): TaskSystem = {
    val akkaconf = ConfigFactory.parseResources("akkaoverrides.conf")

    val conf = if (extraConf.isDefined)
      com.typesafe.config.ConfigFactory.defaultOverrides.withFallback(extraConf.get).withFallback(akkaconf).withFallback(defaultConf).withFallback(config.global)
    else com.typesafe.config.ConfigFactory.defaultOverrides.withFallback(akkaconf).withFallback(defaultConf).withFallback(config.global)
    new TaskSystem(MasterSlaveGridEngineChosenFromConfig, conf)
  }
  def customTaskSystem(hostConfig: MasterSlaveConfiguration, extraConf: Config): TaskSystem = {
    val akkaconf = ConfigFactory.parseResources("akkaoverrides.conf")

    val conf = com.typesafe.config.ConfigFactory.defaultOverrides.withFallback(extraConf).withFallback(akkaconf).withFallback(com.typesafe.config.ConfigFactory.defaultReference)
    new TaskSystem(hostConfig, conf)
  }

  type CompFun[A <: Prerequisitive[A], B <: Result] = A => ComputationEnvironment => B

  class TaskDefinition[A <: Prerequisitive[A], B <: Result](val computation: CompFun[A, B], val taskID: String) {

    def this(computation: CompFun[A, B]) = this(computation, computation.getClass.getName)

    def apply(a: A)(resource: CPUMemoryRequest)(implicit components: TaskSystemComponents): ProxyTaskActorRef[A, B] = newTask[B, A](a, resource, computation, taskID)

  }
  def TaskDefinition[A <: Prerequisitive[A], B <: Result](computation: CompFun[A, B]) = new TaskDefinition(computation)

  def NamedTaskDefinition[A <: Prerequisitive[A], B <: Result](taskID: String)(computation: CompFun[A, B]) = new TaskDefinition(computation, taskID)

  case class UpdatePrerequisitive[A <: Prerequisitive[A], B <: Result](pf: PartialFunction[(A, B), A]) extends PartialFunction[(A, B), A] {
    def apply(v1: (A, B)) = pf.apply(v1)
    def isDefinedAt(x: (A, B)) = pf.isDefinedAt(x)
  }

  def identity[A <: Prerequisitive[A]]: UpdatePrerequisitive[A, Result] = UpdatePrerequisitive[A, Result] {
    case (x, _) => x
  }

  case class STP1[A1](a1: Option[A1]) extends SimplePrerequisitive[STP1[A1]]
  case class STP2[A1, A2](a1: Option[A1], a2: Option[A2]) extends SimplePrerequisitive[STP2[A1, A2]]

  implicit def tupleConverter[A1](t: (A1)): STP1[A1] = STP1(Some(t))
  implicit def tupleConverter[A1, A2](t: (A1, A2)): STP2[A1, A2] = STP2(Some(t._1), Some(t._2))

  def newTask[A <: Result, B <: Prerequisitive[B]](
    prerequisitives: B,
    resource: CPUMemoryRequest = CPUMemoryRequest(cpu = 1, memory = 500),
    f: CompFun[B, A],
    taskID: String
  )(implicit components: TaskSystemComponents): ProxyTaskActorRef[B, A] =
    {
      implicit val queue = components.queue
      implicit val fileService = components.fs
      implicit val cache = components.cache
      implicit val context = components.actorsystem
      implicit val prefix = components.filePrefix

      val taskID1 = taskID

      ProxyTaskActorRef[B, A](
        context.actorOf(
          Props(
            new ProxyTask(queue.actor, fileService.actor, prefix, cache.actor) {
              type MyPrerequisitive = B
              type MyResult = A
              def emptyResultSet = prerequisitives
              override def resourceConsumed = resource

              val runTaskClass = f.getClass
              val taskID = taskID1
            }
          ).withDispatcher("proxytask-dispatcher")
        )
      )
    }

  def MasterSlaveGridEngineChosenFromConfig: MasterSlaveConfiguration = {
    if (config.disableRemoting) LocalConfigurationFromConfig
    else config.global.getString("hosts.gridengine") match {
      case x if x == "LSF" => LSFMasterSlave
      case x if x == "SGE" => SGEMasterSlave
      case x if x == "EC2" => EC2MasterSlave
      case x if x == "NOENGINE" => MasterSlaveFromConfig
      case _ => MasterSlaveFromConfig
    }
  }

  def getLogger(sourceObject: AnyRef)(implicit as: ActorSystem) = {
    implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
      def genString(o: AnyRef): String = o.getClass.getName
      override def getClazz(o: AnyRef): Class[_] = o.getClass
    }
    akka.event.Logging(as, sourceObject)(logSource)
  }

  def getApplicationLogger(sourceObject: AnyRef)(implicit as: ActorSystem) = {
    implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
      def genString(o: AnyRef): String = "APPLICATION-" + o.getClass.getName
      override def getClazz(o: AnyRef): Class[_] = o.getClass
    }
    akka.event.Logging(as, sourceObject)(logSource)
  }

  sealed trait GridEngine
  case object LSFGrid extends GridEngine
  case object EC2Grid extends GridEngine
  case object SGEGrid extends GridEngine
  case object NoGrid extends GridEngine
  case object SSHGrid extends GridEngine

}
