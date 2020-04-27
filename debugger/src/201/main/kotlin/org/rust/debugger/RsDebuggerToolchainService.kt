/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.download.DownloadableFileDescription
import com.intellij.util.download.DownloadableFileService
import com.intellij.util.io.Decompressor
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBBinUrlProvider
import org.rust.debugger.settings.RsDebuggerSettings
import org.rust.openapiext.pluginDirInSystem
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

@Service
class RsDebuggerToolchainService {

    fun getLLDBStatus(lldbPath: String? = RsDebuggerSettings.getInstance().lldbPath): LLDBStatus {
        if (lldbPath.isNullOrEmpty()) return LLDBStatus.NeedToDownload

        val (frameworkPath, frontendPath) = when {
            SystemInfo.isMac -> "LLDB.framework" to "LLDBFrontend"
            SystemInfo.isUnix -> "lib/liblldb.so" to "bin/LLDBFrontend"
            SystemInfo.isWindows -> {
                val binaryDir = "${if (SystemInfo.is32Bit) "x86" else "x64"}/bin"
                "$binaryDir/liblldb.dll" to "$binaryDir/LLDBFrontend.exe"
            }
            else -> return LLDBStatus.Unavailable
        }

        val frameworkFile = File(FileUtil.join(lldbPath, frameworkPath))
        val frontendFile = File(FileUtil.join(lldbPath, frontendPath))
        if (!frameworkFile.exists() || !frontendFile.exists()) return LLDBStatus.NeedToDownload

        val versions = loadLLDBVersions()
        val (lldbFrameworkUrl, lldbFrontendUrl) = lldbUrls ?: return LLDBStatus.Unavailable

        val lldbFrameworkVersion = fileNameWithoutExtension(lldbFrameworkUrl.toString())
        val lldbFrontendVersion = fileNameWithoutExtension(lldbFrontendUrl.toString())

        if (versions[LLDB_FRAMEWORK_PROPERTY_NAME] != lldbFrameworkVersion ||
            versions[LLDB_FRONTEND_PROPERTY_NAME] != lldbFrontendVersion) return LLDBStatus.NeedToUpdate

        return LLDBStatus.Binaries(frameworkFile, frontendFile)
    }

    fun downloadDebugger(
        onSuccess: (File) -> Unit,
        onFailure: () -> Unit
    ) {
        val (lldbFrameworkUrl, lldbFrontendUrl) = lldbUrls ?: run {
            runInEdt { onFailure() }
            return
        }

        val task: Task.Backgroundable = object : Task.Backgroundable(null, "Download debugger") {
            override fun shouldStartInBackground(): Boolean = false
            override fun run(indicator: ProgressIndicator) {
                try {
                    val lldbDir = downloadAndUnarchive(lldbFrameworkUrl.toString(), lldbFrontendUrl.toString())
                    runInEdt {
                        Notifications.Bus.notify(Notification(
                            RUST_DEBUGGER_GROUP_ID,
                            "Debugger",
                            "Debugger successfully downloaded",
                            NotificationType.INFORMATION
                        ))
                        onSuccess(lldbDir)
                    }
                } catch (e: IOException) {
                    LOG.warn("Can't download debugger", e)
                    runInEdt {
                        Notifications.Bus.notify(Notification(
                            RUST_DEBUGGER_GROUP_ID,
                            "Debugger",
                            "Debugger downloading failed",
                            NotificationType.ERROR
                        ))
                        onFailure()
                    }
                }
            }
        }
        val processIndicator = BackgroundableProcessIndicator(task)
        processIndicator.isIndeterminate = false
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, processIndicator)
    }

    private val lldbUrls: Pair<URL, URL>?
        get() {
            return when {
                SystemInfo.isMac -> LLDBBinUrlProvider.lldb.macX64 to LLDBBinUrlProvider.lldbFrontend.macX64
                SystemInfo.isLinux -> LLDBBinUrlProvider.lldb.linuxX64 to LLDBBinUrlProvider.lldbFrontend.linuxX64
                SystemInfo.isWindows -> {
                    if (SystemInfo.is64Bit) {
                        LLDBBinUrlProvider.lldb.winX64 to LLDBBinUrlProvider.lldbFrontend.winX64
                    } else {
                        LLDBBinUrlProvider.lldb.winX86 to LLDBBinUrlProvider.lldbFrontend.winX86
                    }
                }
                else -> return null
            }
        }

    @Throws(IOException::class)
    private fun downloadAndUnarchive(lldbFrameworkUrl: String, lldbFrontendUrl: String): File {
        val service = DownloadableFileService.getInstance()

        val lldbDir = lldbPath().toFile()

        val versions = loadLLDBVersions()
        val descriptions = mutableListOf<DownloadableFileDescription>()
        var cleanDebuggerDir = false

        fun addFileDescriptorIfNeeded(propertyName: String, url: String) {
            val version = versions[propertyName]?.toString()
            if (version != fileNameWithoutExtension(url)) {
                descriptions += service.createFileDescription(url)
                cleanDebuggerDir = true
            }
        }

        addFileDescriptorIfNeeded(LLDB_FRAMEWORK_PROPERTY_NAME, lldbFrameworkUrl)
        addFileDescriptorIfNeeded(LLDB_FRONTEND_PROPERTY_NAME, lldbFrontendUrl)

        if (cleanDebuggerDir) {
            lldbDir.deleteRecursively()
        }

        val downloader = service.createDownloader(descriptions, "Debugger downloading")
        val downloadDirectory = downloadPath().toFile()
        val downloadResults = downloader.download(downloadDirectory)

        for (result in downloadResults) {
            val downloadUrl = result.second.downloadUrl
            val propertyName = if (downloadUrl == lldbFrameworkUrl) LLDB_FRAMEWORK_PROPERTY_NAME else LLDB_FRONTEND_PROPERTY_NAME
            val archiveFile = result.first
            Unarchiver.unarchive(archiveFile, lldbDir)
            archiveFile.delete()
            versions[propertyName] = fileNameWithoutExtension(downloadUrl)
        }

        saveLLDBVersions(versions)

        return lldbDir
    }

    private fun DownloadableFileService.createFileDescription(url: String): DownloadableFileDescription {
        val fileName = url.substringAfterLast("/")
        return createFileDescription(url, fileName)
    }

    private fun fileNameWithoutExtension(url: String): String {
        return url.substringAfterLast("/").removeSuffix(".zip").removeSuffix(".tar.gz")
    }

    private fun loadLLDBVersions(): Properties {
        val versions = Properties()
        val versionsFile = lldbPath().resolve(LLDB_VERSIONS).toFile()

        if (versionsFile.exists()) {
            try {
                versionsFile.bufferedReader().use { versions.load(it) }
            } catch (e: IOException) {
                LOG.warn("Failed to load `$LLDB_VERSIONS`", e)
            }
        }

        return versions
    }

    private fun saveLLDBVersions(versions: Properties) {
        try {
            versions.store(lldbPath().resolve(LLDB_VERSIONS).toFile().bufferedWriter(), "")
        } catch (e: IOException) {
            LOG.warn("Failed to save `$LLDB_VERSIONS`")
        }
    }

    companion object {
        private val LOG: Logger = Logger.getInstance(RsDebuggerToolchainService::class.java)

        private const val LLDB_VERSIONS: String = "versions.properties"

        private const val LLDB_FRONTEND_PROPERTY_NAME = "lldbFrontend"
        private const val LLDB_FRAMEWORK_PROPERTY_NAME = "lldbFramework"

        const val RUST_DEBUGGER_GROUP_ID = "Rust Debugger"

        private fun downloadPath(): Path = Paths.get(PathManager.getTempPath())
        private fun lldbPath(): Path = pluginDirInSystem().resolve("lldb")

        fun getInstance(): RsDebuggerToolchainService = service()
    }

    private enum class Unarchiver {
        ZIP {
            override val extension: String = "zip"
            override fun createDecompressor(file: File): Decompressor = Decompressor.Zip(file)
        },
        TAR {
            override val extension: String = "tar.gz"
            override fun createDecompressor(file: File): Decompressor = Decompressor.Tar(file)
        };

        protected abstract val extension: String
        protected abstract fun createDecompressor(file: File): Decompressor

        companion object {
            @Throws(IOException::class)
            fun unarchive(archivePath: File, dst: File) {
                val unarchiver = values().find { archivePath.name.endsWith(it.extension) }
                    ?: error("Unexpected archive type: $archivePath")
                unarchiver.createDecompressor(archivePath).extract(dst)
            }
        }
    }

    sealed class LLDBStatus {
        object Unavailable : LLDBStatus()
        object NeedToDownload : LLDBStatus()
        object NeedToUpdate : LLDBStatus()
        data class Binaries(val frameworkFile: File, val frontendFile: File) : LLDBStatus()
    }
}