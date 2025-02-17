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

package tasks.util.config

import tasks.util.SimpleSocketAddress
import com.typesafe.config.Config
import scala.jdk.CollectionConverters._
import tasks.shared.ResourceAvailable
import com.typesafe.config.ConfigRenderOptions

class TasksConfig(load: () => Config) {

  private val lastLoadedAt =
    new java.util.concurrent.atomic.AtomicLong(System.nanoTime)
  private var lastLoaded = load()

  private def maxConfigLoadInterval =
    lastLoaded.getDuration("tasks.maxConfigLoadInterval").toNanos

  def raw: Config = {
    val currentTime = System.nanoTime
    if (false && currentTime - lastLoadedAt.get > maxConfigLoadInterval) {
      scribe.debug("Reload config.")
      val current = load()
      lastLoaded = current
      lastLoadedAt.set(currentTime)
      current
    } else lastLoaded
  }

  val asString = raw.root.render

  val codeVersion = tasks.shared.CodeVersion(raw.getString("tasks.codeVersion"))

  val cacheEnabled = raw.getBoolean("tasks.cache.enabled")

  val cachePath =
    raw.getString("tasks.cache.sharefilecache.path").toLowerCase match {
      case "prefix" => None
      case other =>
        Some(
          tasks.fileservice
            .FileServicePrefix(other.split("/").toVector.filter(_.nonEmpty))
        )
    }

  val askInterval: FD = raw.getDuration("tasks.askInterval")

  val launcherActorHeartBeatInterval: FD =
    raw.getDuration("tasks.failuredetector.heartbeat-interval")

  def fileSendChunkSize = raw.getBytes("tasks.fileSendChunkSize").toInt

  def resubmitFailedTask = raw.getBoolean("tasks.resubmitFailedTask")

  def verifySharedFileInCache = raw.getBoolean("tasks.verifySharedFileInCache")

  val disableRemoting = raw.getBoolean("tasks.disableRemoting")

  val slaveWorkingDirectory =
    raw.getString("tasks.elastic.workerWorkingDirectory")

  val slavePackageName =
    raw.getString("tasks.elastic.workerPackageName")

  def skipContentHashVerificationAfterCache =
    raw.getBoolean("tasks.skipContentHashVerificationAfterCache")
  def skipContentHashCreationUponImport =
    raw.getBoolean("tasks.skipContentHashCreationUponImport")

  val acceptableHeartbeatPause: FD =
    raw.getDuration("tasks.failuredetector.acceptable-heartbeat-pause")

  val hostImage = if (raw.hasPath("hosts.image") ) Some(raw.getString("hosts.image")) else None

  val hostNumCPU = raw.getInt("hosts.numCPU")

  val hostGPU = raw.getIntList("hosts.gpus").asScala.toList.map(_.toInt) ++ raw
    .getString("hosts.gpusAsCommaString")
    .split(",")
    .toList
    .filter(_.nonEmpty)
    .map(_.toInt)

  val hostRAM = raw.getInt("hosts.RAM")

  val hostScratch = raw.getInt("hosts.scratch")

  val hostName = raw.getString("hosts.hostname")
  val hostNameExternal = if (raw.hasPath("hosts.hostnameExternal")) Some(raw.getString("hosts.hostnameExternal")) else None

  val hostPort = raw.getInt("hosts.port")

  val masterAddress =
    if (raw.hasPath("hosts.master")) {
      val h = raw.getString("hosts.master").split(":")(0)
      val p = raw.getString("hosts.master").split(":")(1).toInt
      Some(SimpleSocketAddress(h, p))
    } else None

  val startApp = raw.getBoolean("hosts.app")

  val storageURI =
    new java.net.URI(raw.getString("tasks.fileservice.storageURI"))

  val proxyStorage = raw.getBoolean("tasks.fileservice.proxyStorage")

  val parallelismOfCacheAccessibilityCheck =
    raw.getInt("tasks.cache.accessibility-check-parallelism")

  val sshHosts = raw.getObject("tasks.elastic.ssh.hosts")

  val elasticSupport = raw.getString("tasks.elastic.engine")

  def idleNodeTimeout: FD = raw.getDuration("tasks.elastic.idleNodeTimeout")

  def cacheTimeout: FD = raw.getDuration("tasks.cache.timeout")

  def maxNodes = raw.getInt("tasks.elastic.maxNodes")

  def maxPendingNodes = raw.getInt("tasks.elastic.maxPending")

  def maxNodesCumulative = raw.getInt("tasks.elastic.maxNodesCumulative")

  val queueCheckInterval: FD =
    raw.getDuration("tasks.elastic.queueCheckInterval")

  val queueCheckInitialDelay: FD =
    raw.getDuration("tasks.elastic.queueCheckInitialDelay")

  val nodeKillerMonitorInterval: FD =
    raw.getDuration("tasks.elastic.nodeKillerMonitorInterval")

  def jvmMaxHeapFactor = raw.getDouble("tasks.elastic.jvmMaxHeapFactor")

  def logQueueStatus = raw.getBoolean("tasks.elastic.logQueueStatus")

  val awsRegion: String = raw.getString("tasks.elastic.aws.region")

  def spotPrice: Double = raw.getDouble("tasks.elastic.aws.spotPrice")

  def amiID: String = raw.getString("tasks.elastic.aws.ami")

  def securityGroup: String = raw.getString("tasks.elastic.aws.securityGroup")

  def ec2InstanceTypes =
    raw.getConfigList("tasks.elastic.aws.instances").asScala.toList.map {
      conf =>
        val name = conf.getString("name")
        val cpu = conf.getInt("cpu")
        val ram = conf.getInt("ram")
        val gpu = conf.getInt("gpu")
        name -> ResourceAvailable(
          cpu,
          ram,
          Int.MaxValue,
          0 until gpu toList,
          None
        )
    }

  def securityGroups: List[String] =
    raw.getStringList("tasks.elastic.aws.securityGroups").asScala.toList

  def subnetId = raw.getString("tasks.elastic.aws.subnetId")

  def keyName = raw.getString("tasks.elastic.aws.keyName")

  def additionalJavaCommandline =
    raw.getString("tasks.elastic.javaCommandLine")

  def iamRole = {
    val s = raw.getString("tasks.elastic.aws.iamRole")
    if (s == "" || s == "-") None
    else Some(s)
  }

  def placementGroup: Option[String] =
    raw.getString("tasks.elastic.aws.placementGroup") match {
      case x if x == "" => None
      case x            => Some(x)
    }

  val s3RegionProfileName =
    if (raw.hasPath("tasks.s3.regionProfileName"))
      Some(raw.getString("tasks.s3.regionProfileName"))
    else None

  val s3ServerSideEncryption = raw.getString("tasks.s3.serverSideEncryption")

  val s3CannedAcl = raw.getStringList("tasks.s3.cannedAcls").asScala.toList

  val s3GrantFullControl = raw
    .getStringList("tasks.s3.grantFullControl")
    .asScala
    .toList

  val s3UploadParallelism = raw.getInt("tasks.s3.uploadParallelism")

  val httpRemoteEnabled = raw.getBoolean("tasks.fileservice.remote.http")
  val s3RemoteEnabled = raw.getBoolean("tasks.fileservice.remote.s3")

  def instanceTags =
    raw
      .getStringList("tasks.elastic.aws.tags")
      .asScala
      .grouped(2)
      .map(x => x(0) -> x(1))
      .toList

  val terminateMaster = raw.getBoolean("tasks.elastic.aws.terminateMaster")

  val actorSystemName = raw.getString("tasks.akka.actorsystem.name")

  val addShutdownHook = raw.getBoolean("tasks.addShutdownHook")

  val uiFqcn = raw.getString("tasks.ui.fqcn")

  val uiServerHost = raw.getString("tasks.ui.queue.host")

  val uiServerPort = raw.getInt("tasks.ui.queue.port")

  val appUIServerHost = raw.getString("tasks.ui.app.host")

  val appUIServerPort = raw.getInt("tasks.ui.app.port")

  def kubernetesImageName = raw.getString("tasks.kubernetes.image")
  val kubernetesImageApplicationSubPath = raw.getString("tasks.kubernetes.imageApplicationSubPath")

  def kubernetesHostNameOrIPEnvVar =
    raw.getString("tasks.kubernetes.hostnameOrIPEnvVar")
  def kubernetesCpuLimitEnvVar =
    raw.getString("tasks.kubernetes.cpuLimitEnvVar")
  def kubernetesRamLimitEnvVar =
    raw.getString("tasks.kubernetes.ramLimitEnvVar")
  def kubernetesScratchLimitEnvVar =
    raw.getString("tasks.kubernetes.scratchLimitEnvVar")

  def kubernetesCpuExtra = raw.getInt("tasks.kubernetes.extralimits.cpu")
  def kubernetesCpuMin = raw.getInt("tasks.kubernetes.minimumlimits.cpu")
  def kubernetesRamExtra = raw.getInt("tasks.kubernetes.extralimits.ram")
  def kubernetesRamMin = raw.getInt("tasks.kubernetes.minimumlimits.ram")

  def kubernetesPodSpec = {
    
    if (raw.hasPath("tasks.kubernetes.podSpec"))
      Some(raw.getConfig("tasks.kubernetes.podSpec").root().render(ConfigRenderOptions.concise()))
    else None
  }

  def kubernetesNamespace = raw.getString("tasks.kubernetes.namespace")

  def kubernetesImagePullPolicy =
    raw.getString("tasks.kubernetes.image-pull-policy")

  val workerMainClass = raw.getString("tasks.worker-main-class")

  val createFilePrefixForTaskId =
    raw.getBoolean("tasks.createFilePrefixForTaskId")

  def allowDeletion = raw.getBoolean("tasks.fileservice.allowDeletion")

  def allowOverwrite = raw.getBoolean("tasks.fileservice.allowOverwrite")

  def folderFileStorageCompleteFileCheck =
    raw.getBoolean("tasks.fileservice.folderFileStorageCompleteFileCheck")

  def pendingNodeTimeout = raw.getDuration("tasks.elastic.pendingNodeTimeout")

  val checkTempFolderOnSlaveInitialization =
    raw.getBoolean("tasks.elastic.checktempfolder")

  val trackerFqcn = raw.getString("tasks.tracker.fqcn")

  val resourceUtilizationLogFile = raw.getString("tasks.tracker.logFile")

  def trackDataFlow = raw.getBoolean("tasks.queue.trackDataFlow")

  val parallelismOfReadingHistoryFiles =
    raw.getInt("tasks.queue.track-data-flow-history-file-read-parallelism")

  val saveTaskDescriptionInCache =
    raw.getBoolean("tasks.cache.saveTaskDescription")

  val writeFileHistories =
    raw.getBoolean("tasks.fileservice.writeFileHistories")

  val shWorkDir = raw.getString("tasks.elastic.sh.workdir")

  val connectToProxyFileServiceOnMain =
    raw.getBoolean("tasks.fileservice.connectToProxy")

  val storageEncryptionKey =
    if (raw.hasPath("tasks.fileservice.encryptionKey"))
      Some(raw.getString("tasks.fileservice.encryptionKey"))
    else None

}
