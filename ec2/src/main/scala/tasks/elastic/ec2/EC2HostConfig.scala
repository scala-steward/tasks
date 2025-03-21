package tasks.elastic.ec2

import tasks.deploy._
import tasks.util.config.TasksConfig
import tasks.util.EC2Operations
import tasks.util.SimpleSocketAddress

class EC2MasterFollower(val config: EC2Config)
    extends HostConfigurationFromConfig {

  private lazy val myhostname =
    EC2Operations.readMetadata("local-hostname").head

  override lazy val myAddress = SimpleSocketAddress(myhostname, myPort)

  private lazy val instancetype = EC2Operations.currentInstanceType(config)

  override lazy val availableMemory = instancetype._2.memory

  override lazy val availableCPU = instancetype._2.cpu

}
