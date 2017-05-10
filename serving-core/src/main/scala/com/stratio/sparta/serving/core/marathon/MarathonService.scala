/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparta.serving.core.marathon

import java.util.Calendar

import akka.actor.{ActorContext, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.Timeout
import com.stratio.sparta.serving.core.config.SpartaConfig
import com.stratio.sparta.serving.core.constants.AppConstant._
import com.stratio.sparta.serving.core.constants.{AkkaConstant, AppConstant}
import com.stratio.sparta.serving.core.models.enumerators.PolicyStatusEnum._
import com.stratio.sparta.serving.core.models.policy.{PhaseEnum, PolicyErrorModel, PolicyModel, PolicyStatusModel}
import com.stratio.sparta.serving.core.models.submit.SubmitRequest
import com.stratio.sparta.serving.core.utils.PolicyStatusUtils
import com.stratio.tikitakka.common.message._
import com.stratio.tikitakka.common.model.{ContainerId, ContainerInfo, CreateApp, Parameter, Volume}
import com.stratio.tikitakka.core.UpAndDownActor
import com.stratio.tikitakka.updown.UpAndDownComponent
import com.typesafe.config.Config
import org.apache.curator.framework.CuratorFramework
import play.api.libs.json._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.Source
import scala.util.{Properties, Try}

//scalastyle:off
class MarathonService(context: ActorContext,
                      val curatorFramework: CuratorFramework,
                      policyModel: Option[PolicyModel],
                      sparkSubmitRequest: Option[SubmitRequest]) extends OauthTokenUtils with PolicyStatusUtils {

  def this(context: ActorContext,
           curatorFramework: CuratorFramework,
           policyModel: PolicyModel,
           sparkSubmitRequest: SubmitRequest) =
    this(context, curatorFramework, Option(policyModel), Option(sparkSubmitRequest))

  def this(context: ActorContext, curatorFramework: CuratorFramework) = this(context, curatorFramework, None, None)

  /* Implicit variables */

  implicit val actorSystem: ActorSystem = context.system
  implicit val timeout: Timeout = Timeout(AkkaConstant.DefaultTimeout.seconds)
  implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(actorSystem))

  /* Constant variables */

  val AppMainClass = "com.stratio.sparta.driver.MarathonDriver"
  val DefaultMarathonTemplateFile = "/etc/sds/sparta/marathon-app-template.json"
  val MarathonApp = "marathon"
  val DefaultSpartaDockerImage = "qa.stratio.com/stratio/sparta:1.4.0-SNAPSHOT"
  val HostMesosNativeLibPath = "/opt/mesosphere/lib"
  val HostMesosNativePackagesPath = "/opt/mesosphere/packages"
  val HostMesosLib = s"$HostMesosNativeLibPath"
  val HostMesosNativeLib = s"$HostMesosNativeLibPath/libmesos.so"
  val ServiceName = policyModel.fold("") { policy => s"sparta/workflows/${policy.name}" }
  val DefaultMemory = 1024
  val Krb5ConfFile = "/etc/krb5.conf:/etc/krb5.conf:ro"
  val ContainerCertificatePath = "/etc/ssl/certs/java/cacerts"
  val HostCertificatePath = "/etc/pki/ca-trust/extracted/java/cacerts"

  /* Environment variables to Marathon Application */

  val AppTypeEnv = "SPARTA_APP_TYPE"
  val MesosNativeJavaLibraryEnv = "MESOS_NATIVE_JAVA_LIBRARY"
  val LdLibraryEnv = "LD_LIBRARY_PATH"
  val AppMainEnv = "SPARTA_MARATHON_MAIN_CLASS"
  val AppJarEnv = "SPARTA_MARATHON_JAR"
  val VaultEnable = "VAULT_ENABLE"
  val VaultHostEnv = "VAULT_HOST"
  val VaultPortEnv = "VAULT_PORT"
  val VaultTokenEnv = "VAULT_TOKEN"
  val PolicyIdEnv = "SPARTA_POLICY_ID"
  val ZookeeperConfigEnv = "SPARTA_ZOOKEEPER_CONFIG"
  val DetailConfigEnv = "SPARTA_DETAIL_CONFIG"
  val AppHeapSizeEnv = "MARATHON_APP_HEAP_SIZE"
  val AppHeapMinimunSizeEnv = "MARATHON_APP_HEAP_MINIMUM_SIZE"
  val SparkHomeEnv = "SPARK_HOME"
  val HadoopUserNameEnv = "HDFS_USER_NAME"
  val HdfsConfFromUriEnv = "HDFS_CONF_FROM_URI"
  val CoreSiteFromUriEnv = "CORE_SITE_FROM_URI"
  val HdfsConfFromDfsEnv = "HDFS_CONF_FROM_DFS"
  val HdfsConfFromDfsNotSecuredEnv = "HDFS_CONF_FROM_DFS_NOT_SECURED"
  val DefaultFsEnv = "HADOOP_FS_DEFAULT_NAME"
  val DefaultHdfsConfUriEnv = "HADOOP_CONF_URI"
  val HadoopConfDirEnv = "HADOOP_CONF_DIR"
  val ServiceLogLevelEnv = "SERVICE_LOG_LEVEL"
  val SpartaLogLevelEnv = "SPARTA_LOG_LEVEL"
  val SparkLogLevelEnv = "SPARK_LOG_LEVEL"
  val ZookeeperLogLevelEnv = "ZOOKEEPER_LOG_LEVEL"
  val HadoopLogLevelEnv = "HADOOP_LOG_LEVEL"
  val DcosServiceName = "DCOS_SERVICE_NAME"
  val Constraints = "MESOS_CONSTRAINTS"
  val HdfsRpcProtectionEnv = "HADOOP_RPC_PROTECTION"
  val HdfsSecurityAuthEnv = "HADOOP_SECURITY_AUTH"
  val HdfsEncryptDataEnv = "HADOOP_DFS_ENCRYPT_DATA_TRANSFER"
  val HdfsTokenUseIpEnv = "HADOOP_SECURITY_TOKEN_USE_IP"
  val HdfsKerberosPrincipalEnv = "HADOOP_NAMENODE_KERBEROS_PRINCIPAL"
  val HdfsKerberosPrincipalPatternEnv = "HADOOP_NAMENODE_KERBEROS_PRINCIPAL_PATTERN"
  val HdfsEncryptDataTransferEnv = "HADOOP_DFS_ENCRYPT_DATA_TRANSFER_CIPHER_SUITES"
  val HdfsEncryptDataBitLengthEnv = "HADOOP_DFS_ENCRYPT_DATA_CIPHER_KEY_BITLENGTH"

  /* Lazy variables */

  lazy val marathonConfig: Config = SpartaConfig.getClusterConfig(Option(ConfigMarathon)).get
  lazy val upAndDownComponent: UpAndDownComponent = SpartaMarathonComponent.apply
  lazy val upAndDownActor: ActorRef = actorSystem.actorOf(Props(new UpAndDownActor(upAndDownComponent)),
    s"${AkkaConstant.UpDownMarathonActor}-${Calendar.getInstance().getTimeInMillis}")

  /* PUBLIC METHODS */

  def launch(detailExecMode: String): Unit = {
    assert(policyModel.isDefined && sparkSubmitRequest.isDefined, "Is mandatory specify one policy and the request")
    val createApp = addRequirements(getMarathonAppFromFile, policyModel.get, sparkSubmitRequest.get)
    for {
      response <- (upAndDownActor ? UpServiceRequest(createApp, Try(getToken).toOption)).mapTo[UpAndDownMessage]
    } response match {
      case response: UpServiceFails =>
        val information = s"Error when launching Sparta Marathon App to Marathon API with id: ${response.appInfo.id}"
        log.error(information)
        updateStatus(PolicyStatusModel(
          id = policyModel.get.id.get,
          status = Failed,
          statusInfo = Option(information),
          marathonId = Option(createApp.id),
          lastError = Option(PolicyErrorModel(information, PhaseEnum.Execution, response.msg))))
        log.error(s"Service ${response.appInfo.id} can't be deployed: ${response.msg}")
      case response: UpServiceResponse =>
        val information = s"Sparta Marathon App launched correctly to Marathon API with id: ${response.appInfo.id}"
        log.info(information)
        updateStatus(PolicyStatusModel(id = policyModel.get.id.get, status = Uploaded,
          marathonId = Option(createApp.id), statusInfo = Option(information)))
      case _ =>
        val information = "Unrecognized message received from Marathon API"
        log.warn(information)
        updateStatus(PolicyStatusModel(id = policyModel.get.id.get, status = NotDefined,
          statusInfo = Option(information)))
    }
  }

  def kill(containerId: String): Unit = upAndDownActor ! DownServiceRequest(ContainerId(containerId))

  /* PRIVATE METHODS */

  private def marathonJar: Option[String] =
    Try(marathonConfig.getString("jar")).toOption.orElse(Option(AppConstant.DefaultMarathonDriverURI))

  private def mesosNativeLibrary: Option[String] = Properties.envOrNone(MesosNativeJavaLibraryEnv)

  private def ldNativeLibrary: Option[String] = Properties.envOrNone(MesosNativeJavaLibraryEnv) match {
    case Some(_) => None
    case None => Option(HostMesosLib)
  }

  private def mesosphereLibPath: String =
    Try(marathonConfig.getString("mesosphere.lib")).toOption.getOrElse(HostMesosNativeLibPath)

  private def mesospherePackagesPath: String =
    Try(marathonConfig.getString("mesosphere.packages")).toOption.getOrElse(HostMesosNativePackagesPath)

  private def spartaDockerImage: String =
    Try(marathonConfig.getString("docker.image")).toOption.getOrElse(DefaultSpartaDockerImage)

  private def envSparkHome: Option[String] = Properties.envOrNone(SparkHomeEnv)

  private def envConstraint: Option[String] = Properties.envOrNone(Constraints)

  private def envVaultHost: Option[String] = Properties.envOrNone(VaultHostEnv)

  private def envVaulPort: Option[String] = Properties.envOrNone(VaultPortEnv)

  private def envVaultToken: Option[String] = Properties.envOrNone(VaultTokenEnv)

  private def envHadoopUserName: Option[String] = Properties.envOrNone(HadoopUserNameEnv)

  private def envHdfsConfFromUri: Option[String] = Properties.envOrNone(HdfsConfFromUriEnv)

  private def envCoreSiteFromUri: Option[String] = Properties.envOrNone(CoreSiteFromUriEnv)

  private def envHdfsConfFromDfs: Option[String] = Properties.envOrNone(HdfsConfFromDfsEnv)

  private def envHdfsConfFromDfsNotSecured: Option[String] = Properties.envOrNone(HdfsConfFromDfsNotSecuredEnv)

  private def envDefaultFs: Option[String] = Properties.envOrNone(DefaultFsEnv)

  private def envHdfsRpcProtection: Option[String] = Properties.envOrNone(HdfsRpcProtectionEnv)

  private def envHdfsSecurityAuth: Option[String] = Properties.envOrNone(HdfsSecurityAuthEnv)

  private def envHdfsEncryptData: Option[String] = Properties.envOrNone(HdfsEncryptDataEnv)

  private def envHdfsTokenUseIp: Option[String] = Properties.envOrNone(HdfsTokenUseIpEnv)

  private def envHdfsKerberosPrincipal: Option[String] = Properties.envOrNone(HdfsKerberosPrincipalEnv)

  private def envHdfsKerberosPrincipalPattern: Option[String] = Properties.envOrNone(HdfsKerberosPrincipalPatternEnv)

  private def envHdfsEncryptDataTransfer: Option[String] = Properties.envOrNone(HdfsEncryptDataTransferEnv)

  private def envHdfsEncryptDataBitLength: Option[String] = Properties.envOrNone(HdfsEncryptDataBitLengthEnv)

  private def envDefaultHdfsConfUri: Option[String] = Properties.envOrNone(DefaultHdfsConfUriEnv)

  private def envHadoopConfDir: Option[String] = Properties.envOrNone(HadoopConfDirEnv)

  private def envServiceLogLevel: Option[String] = Properties.envOrNone(ServiceLogLevelEnv)

  private def envSpartaLogLevel: Option[String] = Properties.envOrNone(SpartaLogLevelEnv)

  private def envSparkLogLevel: Option[String] = Properties.envOrNone(SparkLogLevelEnv)

  private def envHadoopLogLevel: Option[String] = Properties.envOrNone(HadoopLogLevelEnv)

  private def getMarathonAppFromFile: CreateApp = {
    val templateFile = Try(marathonConfig.getString("template.file")).toOption.getOrElse(DefaultMarathonTemplateFile)
    val fileContent = Source.fromFile(templateFile).mkString
    Json.parse(fileContent).as[CreateApp]
  }

  private def getKrb5ConfVolume: Seq[Parameter] = Properties.envOrNone(VaultEnable) match {
    case Some(vaultEnable) if Try(vaultEnable.toBoolean).getOrElse(false) =>
      Seq(Parameter("volume", Krb5ConfFile))
    case None =>
      Seq.empty[Parameter]
  }

  private def transformMemoryToInt(memory: String): Int = Try(memory match {
    case mem if mem.contains("G") => mem.replace("G", "").toInt * 1024
    case mem if mem.contains("g") => mem.replace("g", "").toInt * 1024
    case mem if mem.contains("m") => mem.replace("m", "").toInt
    case mem if mem.contains("M") => mem.replace("M", "").toInt
    case _ => memory.toInt
  }).getOrElse(DefaultMemory)

  private def addRequirements(app: CreateApp, policyModel: PolicyModel, submitRequest: SubmitRequest): CreateApp = {
    val newCpus = submitRequest.sparkConfigurations.get("spark.driver.cores").map(_.toDouble + 1d).getOrElse(app.cpus)
    val newMem = submitRequest.sparkConfigurations.get("spark.driver.memory").map(transformMemoryToInt(_) + 1024)
      .getOrElse(app.mem)
    val envFromSubmit = submitRequest.sparkConfigurations.flatMap { case (key, value) =>
      if (key.startsWith("spark.mesos.driverEnv.")) {
        Option((key.split("spark.mesos.driverEnv.").tail.head, value))
      } else None
    }

    val subProperties = substitutionProperties(policyModel, submitRequest, newMem)
    val newEnv = app.env.map { properties =>
      properties.flatMap { case (k, v) =>
        if (v == "???")
          subProperties.get(k).map(vParsed => (k, vParsed))
        else Some((k, v))
      } ++ envFromSubmit
    }.orElse(Option(envFromSubmit))
    val newLabels = app.labels.flatMap { case (k, v) =>
      if (v == "???")
        subProperties.get(k).map(vParsed => (k, vParsed))
      else Some((k, v))
    }
    val certificatesVolume = Volume(ContainerCertificatePath, HostCertificatePath, "RO")
    val newDockerContainerInfo = mesosNativeLibrary match {
      case Some(_) =>
        ContainerInfo(app.container.docker.copy(image = spartaDockerImage,
          volumes = Option(Seq(certificatesVolume)),
          parameters = Option(getKrb5ConfVolume)
        ))
      case None => ContainerInfo(app.container.docker.copy(volumes = Option(Seq(
        certificatesVolume,
        Volume(HostMesosNativeLibPath, mesosphereLibPath, "RO"),
        Volume(HostMesosNativePackagesPath, mesospherePackagesPath, "RO"))),
        image = spartaDockerImage,
        parameters = Option(getKrb5ConfVolume)
      ))
    }

    app.copy(
      id = ServiceName,
      cpus = newCpus,
      mem = newMem,
      env = newEnv,
      labels = newLabels,
      container = newDockerContainerInfo
    )
  }

  private def substitutionProperties(policyModel: PolicyModel,
                                     submitRequest: SubmitRequest,
                                     memory: Int): Map[String, String] =
    Map(
      AppMainEnv -> Option(AppMainClass),
      AppTypeEnv -> Option(MarathonApp),
      MesosNativeJavaLibraryEnv -> mesosNativeLibrary.orElse(Option(HostMesosNativeLib)),
      LdLibraryEnv -> ldNativeLibrary,
      AppJarEnv -> marathonJar,
      ZookeeperConfigEnv -> submitRequest.driverArguments.get("zookeeperConfig"),
      DetailConfigEnv -> submitRequest.driverArguments.get("detailConfig"),
      PolicyIdEnv -> policyModel.id,
      VaultHostEnv -> envVaultHost,
      VaultPortEnv -> envVaulPort,
      VaultTokenEnv -> envVaultToken,
      AppHeapSizeEnv -> Option(s"-Xmx${memory}m"),
      AppHeapMinimunSizeEnv -> Option(s"-Xms${memory.toInt / 2}m"),
      SparkHomeEnv -> envSparkHome,
      HadoopUserNameEnv -> envHadoopUserName,
      HdfsConfFromUriEnv -> envHdfsConfFromUri,
      CoreSiteFromUriEnv -> envCoreSiteFromUri,
      HdfsConfFromDfsEnv -> envHdfsConfFromDfs,
      HdfsConfFromDfsNotSecuredEnv -> envHdfsConfFromDfsNotSecured,
      DefaultFsEnv -> envDefaultFs,
      DefaultHdfsConfUriEnv -> envDefaultHdfsConfUri,
      HadoopConfDirEnv -> envHadoopConfDir,
      ServiceLogLevelEnv -> envServiceLogLevel,
      SpartaLogLevelEnv -> envSpartaLogLevel,
      SparkLogLevelEnv -> envSparkLogLevel,
      HadoopLogLevelEnv -> envHadoopLogLevel,
      HdfsRpcProtectionEnv -> envHdfsRpcProtection,
      HdfsSecurityAuthEnv -> envHdfsSecurityAuth,
      HdfsEncryptDataEnv -> envHdfsEncryptData,
      HdfsTokenUseIpEnv -> envHdfsTokenUseIp,
      HdfsKerberosPrincipalEnv -> envHdfsKerberosPrincipal,
      HdfsKerberosPrincipalPatternEnv -> envHdfsKerberosPrincipalPattern,
      HdfsEncryptDataTransferEnv -> envHdfsEncryptDataTransfer,
      HdfsEncryptDataBitLengthEnv -> envHdfsEncryptDataBitLength,
      DcosServiceName -> Option(ServiceName)
    ).flatMap { case (k, v) => v.map(value => Option(k -> value)) }.flatten.toMap
}