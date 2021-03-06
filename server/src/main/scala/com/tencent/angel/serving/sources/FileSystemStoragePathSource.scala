package com.tencent.angel.serving.sources

import java.util
import java.util.{ArrayList, Timer, TimerTask}
import java.util.concurrent.locks.ReentrantReadWriteLock

import com.tencent.angel.config.FileSystemStoragePathSourceConfigProtos.FileSystemStoragePathSourceConfig.ServableVersionPolicy.PolicyChoiceCase
import com.tencent.angel.serving.serving.{FileSystemStoragePathSourceConfig, ServableToMonitor}
import com.tencent.angel.serving.core._
import com.tencent.angel.serving.service.{ModelServer, ServingContext}
import org.apache.commons.io.FilenameUtils
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._


object FileSystemStoragePathSource {

  var AspiredVersionsCallback_ : AspiredVersionsCallback[StoragePath] = null
  var timer: Timer = null


  def create(config: FileSystemStoragePathSourceConfig): FileSystemStoragePathSource = {
    val source: FileSystemStoragePathSource = new FileSystemStoragePathSource(config)
    source.updateConfig(config)
    source
  }
}

class FileSystemStoragePathSource(config: FileSystemStoragePathSourceConfig) extends Source[StoragePath] {

  import FileSystemStoragePathSource._
  var config_ : FileSystemStoragePathSourceConfig = config
  val LOG:Logger = LoggerFactory.getLogger(classOf[FileSystemStoragePathSource])

  private val callbackLock = new ReentrantReadWriteLock()
  private val callbackReadLock: ReentrantReadWriteLock.ReadLock = callbackLock.readLock()
  private val callbackWriteLock: ReentrantReadWriteLock.WriteLock = callbackLock.writeLock()

  private val configLock = new ReentrantReadWriteLock()
  private val configReadLock: ReentrantReadWriteLock.ReadLock = configLock.readLock()
  private val configWriteLock: ReentrantReadWriteLock.WriteLock = configLock.writeLock()


  def updateConfig(config: FileSystemStoragePathSourceConfig): Unit = {
    configWriteLock.lock()
    try {
      if (timer != null && config != config_.getFileSystemPollWaitSeconds) {
        throw InvalidArguments("Changing file_system_poll_wait_seconds is not supported")
      }

      if (config.getFailIfZeroVersionsAtStartup) {
        try {
          failIfZeroVersions(config)
        } catch {
          case e: NotFoundExceptions => LOG.error(e.getMessage)
        }
      }

      if (AspiredVersionsCallback_ != null) {
        unAspireServables(getDeletedServables(config_, config))
      }
      config_ = config
    } finally {
      configWriteLock.unlock()
    }
  }

  // Determines if, for any servables in 'config', the file system doesn't
  // currently contain at least one version under its base path.
  def failIfZeroVersions(config: FileSystemStoragePathSourceConfig): Unit = {
    val versionsByServableName: Map[String, List[ServableData[StoragePath]]] =
      pollFileSystemForConfig(config)
    versionsByServableName.foreach { case (servableName, versions) =>
      if (versions.isEmpty) {
        throw NotFoundExceptions(s"Unable to find a numerical version path for servable: ${servableName} at the path.")
      }
    }
  }

  def unAspireServables(servableNames: Set[String]): Unit = {
    servableNames.map(AspiredVersionsCallback_(_, null))
  }

  // Returns the names of servables that appear in 'old_config' but not in
  // 'new_config'. Assumes both configs are normalized.
  def getDeletedServables(oldConfig: FileSystemStoragePathSourceConfig, newConfig: FileSystemStoragePathSourceConfig
                         ): Set[String] = {
    val newServables = newConfig.getServablesList.asScala.map(x => x.getServableName)

    oldConfig.getServablesList.asScala.filterNot(
      (oldServable: ServableToMonitor) => newServables.contains(oldServable.getServableName)).map(x => x.getServableName).toSet
  }

  override def setAspiredVersionsCallback(callback: AspiredVersionsCallback[StoragePath]): Unit = {
    callbackWriteLock.lock()
    try {
      if (timer != null) {
        LOG.info("SetAspiredVersionsCallback() called multiple times; ignoring this call")
        return
      }
      AspiredVersionsCallback_ = callback
      timer = new Timer("FileSystemStoragePathSource_filesystem_polling_thread")
      timer.schedule(new TimerTask {
        override def run(): Unit = {
          pollFileSystemAndInvokeCallback()
        }
      }, 0, config_.getFileSystemPollWaitSeconds * 1000)

    } finally {
      callbackWriteLock.unlock()
    }
  }

  def pollFileSystemAndInvokeCallback() = {
    configReadLock.lock()
    try {
      val versionsByServableName = pollFileSystemForConfig(config_)
      versionsByServableName.foreach { case (servableName, versions) =>
        AspiredVersionsCallback_(servableName, versions)
      }
    } finally {
      configReadLock.unlock()
    }
  }

  def pollFileSystemForConfig(config: FileSystemStoragePathSourceConfig):
                              Map[String, List[ServableData[StoragePath]]] = {
    val versionsByServableName = mutable.Map[String, List[ServableData[StoragePath]]]()
    try {
      config.getServablesList.asScala.foreach{ servable =>
        val versions = pollFileSystemForServable(servable)
        versionsByServableName += (servable.getServableName -> versions)
      }
    } catch {
      case e: InvalidArguments => {LOG.error(e.getMessage)}
    }
    versionsByServableName.toMap
  }

  def pollFileSystemForServable(servable: ServableToMonitor): List[ServableData[StoragePath]] = {
    if (!SystemFileUtils.dirExist(servable.getBasePath)) {
      throw new Exception(s"Could not find base path ${servable.getBasePath} for servable ${servable.getServableName}")
    }

    //Retrieve a list of base-path children from the file system.
    val children = SystemFileUtils.getChildren(servable.getBasePath)

    val childrenByVersion = indexChildrenByVersion(children)

    val versions = servable.getServableVersionPolicy.getPolicyChoiceCase match {
      case PolicyChoiceCase.LATEST => AspireLastestVersions(servable, childrenByVersion)
      case PolicyChoiceCase.ALL => AspireAllVersions(servable, children)
      case PolicyChoiceCase.SPECIFIC => AspireSpecificVersions(servable, childrenByVersion)
      case PolicyChoiceCase.POLICYCHOICE_NOT_SET  => AspireLastestVersions(servable, childrenByVersion)
    }
    versions
  }

  def AspireVersions(servable: ServableToMonitor, versionRelativePath: String, versionNumber: Long
                    ): ServableData[StoragePath] = {
    val servableId = ServableId(servable.getServableName, versionNumber)
    val fullPath: StoragePath = FilenameUtils.concat(servable.getBasePath, versionRelativePath)
    ServableData[StoragePath](servableId, fullPath)
  }

  def AspireVersionsWithoutChildren(servable: ServableToMonitor, versionNumber: Long
                                   ): ServableData[StoragePath] = {
    val servableId = ServableId(servable.getServableName, versionNumber)
    ServableData[StoragePath](servableId, servable.getBasePath)
  }

  def AspireLastestVersions(servable: ServableToMonitor, childrenByVersion: mutable.LinkedHashMap[Long, String]
                            ): List[ServableData[StoragePath]]= {
    val numServableVersionsToServe = math.max(servable.getServableVersionPolicy.getLatest.getNumVersions, 1)
    val versions = ListBuffer[ServableData[StoragePath]]()
    var numVersionsEmitted = 0
    if (childrenByVersion.isEmpty){
      versions += AspireVersionsWithoutChildren(servable, 1L)
    } else {
      childrenByVersion.foreach { case (versionNumber: Long, child: String) =>
        if (numVersionsEmitted < numServableVersionsToServe) {
          versions += AspireVersions(servable, child, versionNumber)
          numVersionsEmitted += 1
        }
      }
    }
    versions.toList
  }

  def AspireAllVersions(servable: ServableToMonitor, children: Set[String]
                        ): List[ServableData[StoragePath]] = {
    val versions = ListBuffer[ServableData[StoragePath]]()
    var atLeastOneVersionFound = false
    if(children.isEmpty) {
      versions += AspireVersionsWithoutChildren(servable, 1L)
      atLeastOneVersionFound = true
    } else {
      children.foreach{ child =>
        val versionNumber = parseVersionNumber(child)
        if (versionNumber >= 0) {
          versions += AspireVersions(servable, child, versionNumber)
          atLeastOneVersionFound = true
        }
      }
    }
    if (!atLeastOneVersionFound){
      LOG.warn(s"No versions of servable  ${servable.getServableName} found under base path ${servable.getBasePath}")
    }
    versions.toList
  }


  def AspireSpecificVersions(servable: ServableToMonitor, childrenByVersion: mutable.LinkedHashMap[Long, String]
                             ): List[ServableData[StoragePath]] = {
    val versions = ListBuffer[ServableData[StoragePath]]()
    val versionsToServe = servable.getServableVersionPolicy.getSpecific.getVersionsList
    val aspiredVersions = new util.HashSet[Long]()
    if(childrenByVersion.isEmpty) {
      versions += AspireVersionsWithoutChildren(servable, 1L)
      aspiredVersions.add(1L)
    } else {
      childrenByVersion.foreach { case (version_number: Long, child: String) =>
        if (versionsToServe.contains(version_number)) {
          versions += AspireVersions(servable, child, version_number)
          aspiredVersions.add(version_number)
        }
      }
    }
    versionsToServe.asScala.foreach{ version =>
      if (!aspiredVersions.contains(version)) {
        LOG.warn(s"version ${version} of servable ${servable.getServableName} , which was requested to be served as " +
          s"a specific version in the servable's version policy, was not found in the system.")
      }
    }
    if (aspiredVersions.isEmpty){
      LOG.warn(s"No versions of servable  ${servable.getServableName} found under base path ${servable.getBasePath}")
    }
    versions.toList
  }

  def parseVersionNumber(version: String): Long = {
    try {
      version.toLong
    } catch {
      case e: Exception => return -1
    }
    version.toLong
  }

  def indexChildrenByVersion(children: Set[String]): mutable.LinkedHashMap[Long, String] = {
    var childrenByVersion = mutable.HashMap[Long, String]()
    children.foreach { child =>
      val versionNumber = parseVersionNumber(child)
      if (versionNumber >= 0) {
        childrenByVersion += (versionNumber -> child)
      }
    }
    mutable.LinkedHashMap(childrenByVersion.toList.sortWith(_._1>_._1):_*)
  }
}
