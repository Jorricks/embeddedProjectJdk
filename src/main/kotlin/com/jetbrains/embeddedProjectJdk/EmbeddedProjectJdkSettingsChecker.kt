package com.jetbrains.embeddedProjectJdk

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import java.io.File
import java.security.MessageDigest

class EmbeddedProjectJdkSettingsChecker : ProjectActivity  {
  companion object {
    private const val CHECK_INTERVAL_MS = 5000L // 5 seconds
    private const val LOG_ALIVE_INTERVAL = 120 // Log every 12 checks (10 minutes)
  }

  private val myLogger = Logger.getInstance(EmbeddedProjectJdkSettingsChecker::class.java)
  private val checkCounter = AtomicInteger(-1)
  private var job: Job? = null
  private var lastJdkTableHash: String? = null

  override suspend fun execute(project: Project) {
    myLogger.info("Starting background JDK settings checker.")
    startBackgroundChecker(project)
  }

  private fun calculateFileHash(file: File): String? {
    return try {
      if (!file.exists()) return null
      val digest = MessageDigest.getInstance("SHA-256")
      val bytes = file.readBytes()
      val hash = digest.digest(bytes)
      hash.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
      myLogger.warn("Failed to calculate file hash", e)
      null
    }
  }

  private fun shouldCheckJdkSettings(project: Project): Boolean {
    val jdkTableFile = JdkUtil.getJdkTableFile(project)
    val currentHash = calculateFileHash(jdkTableFile)
    
    return if (currentHash == null) {
      myLogger.debug("It appears there is no custom JDK table. Reporting as the same.")
      false
    } else if (lastJdkTableHash == null) {
      lastJdkTableHash = currentHash
      myLogger.debug("Initial run, reporting JDK table file as changed.")
      true // First check should always proceed
    } else if (currentHash != lastJdkTableHash) {
      lastJdkTableHash = currentHash
      myLogger.info("JDK table file has changed.")
      true
    } else {
      myLogger.debug("JDK table file has not changed.")
      false
    }
  }

  private fun startBackgroundChecker(project: Project) {
    job?.cancel()
    job = CoroutineScope(Dispatchers.Default).launch {
      myLogger.info("Starting background thread.")
      while (isActive) {
        val currentCheck = checkCounter.incrementAndGet()
        if (currentCheck % LOG_ALIVE_INTERVAL == 0) {
          myLogger.info("JDK Settings checker is still alive and checking for changes.")
          JdkUtil.createFileWithSuffix(project, "health_check", Instant.now().toString())
          checkCounter.set(0)
        }
        
        if (shouldCheckJdkSettings(project) && JdkUtil.hasDifferentJdkSettings(project)) {
          updateJDKTable(project)
          withContext(Dispatchers.Main) {
            Messages.showMessageDialog(
              project,
              "Updated your JDK settings. You should be able to see your JDK settings change soon.",
              "Update JDKs",
              Messages.getInformationIcon()
            )
          }
          JdkUtil.createFileWithSuffix(project, "updated", Instant.now().toString())
        }
        
        delay(CHECK_INTERVAL_MS)
      }
    }
  }

  private fun updateJDKTable(project: Project) {
    val projectJdkTable = ProjectJdkTable.getInstance()
    val jdkList = JdkUtil.readProjectJdkSettings(project)
    ApplicationManager.getApplication().runWriteAction {
      jdkList.forEach { jdk ->
        val originJdk = projectJdkTable.findJdk(jdk.name)
        if (originJdk != null) {
          myLogger.info("Removed JDK from per project settings: ${jdk.name}")
          projectJdkTable.removeJdk(originJdk)
        }
        projectJdkTable.addJdk(jdk)
        myLogger.info("Add JDK from per project settings: ${jdk.name}")
      }
    }
  }
}
