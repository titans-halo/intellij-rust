/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.tools

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.execution.ParametersListUtil
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.toolchain.RustChannel
import org.rust.ide.actions.RustfmtEditSettingsAction
import org.rust.ide.notifications.showBalloon
import org.rust.lang.core.psi.ext.edition
import org.rust.lang.core.psi.isNotRustFile
import org.rust.openapiext.*
import java.nio.file.Path

fun RsToolchainBase.rustfmt(): Rustfmt = Rustfmt(this)

class Rustfmt(toolchain: RsToolchainBase) : RustupComponent(NAME, toolchain) {

    fun reformatDocumentTextOrNull(cargoProject: CargoProject, document: Document): String? {
        val project = cargoProject.project
        return showRustfmtErrorIfAny(project) {
            createCommandLine(cargoProject, document)
                ?.execute(cargoProject.project, ignoreExitCode = false, stdIn = document.text.toByteArray())
                ?.stdout
        }
    }

    fun createCommandLine(cargoProject: CargoProject, document: Document): GeneralCommandLine? {
        val file = document.virtualFile ?: return null
        if (file.isNotRustFile || !file.isValid) return null

        val project = cargoProject.project
        val configuration = project.rustSettings.rustfmtConfiguration
        val additionalArguments = ParametersListUtil.parse(configuration.command)
        val workingDirectory = configuration.workingDirectory ?: cargoProject.workingDirectory
        val channel = configuration.channel
        val environment = configuration.env.envs

        val arguments = mutableListOf<String>().apply {
            if (channel != RustChannel.DEFAULT) add("+$channel")
            addAll(additionalArguments)

            removeAll { it.startsWith("--emit") }
            add("--emit=stdout")

            if (none { it.startsWith("--config-path") }) {
                val configPath = findConfigPathRecursively(file.parent, stopAt = workingDirectory)
                if (configPath != null) {
                    add("--config-path=$configPath")
                }
            }

            if (none { it.startsWith("--edition") }) {
                val currentRustcVersion = cargoProject.rustcInfo?.version?.semver
                if (currentRustcVersion != null) {
                    val edition = runReadAction {
                        val psiFile = file.toPsiFile(cargoProject.project)
                        psiFile?.edition ?: CargoWorkspace.Edition.EDITION_2018
                    }
                    add("--edition=${edition.presentation}")
                }
            }
        }

        return createBaseCommandLine(arguments, workingDirectory, environment)
    }

    @Throws(ExecutionException::class)
    fun reformatCargoProject(
        cargoProject: CargoProject,
        owner: Disposable = cargoProject.project
    ) {
        val project = cargoProject.project
        val configuration = project.rustSettings.rustfmtConfiguration
        val workingDirectory = configuration.workingDirectory ?: cargoProject.workingDirectory
        val commandLine = CargoCommandLine.forProject(
            cargoProject,
            "fmt",
            listOf("--all", "--") + ParametersListUtil.parse(configuration.command),
            configuration.channel,
            configuration.env
        )

        showRustfmtErrorIfAny(project) {
            project.computeWithCancelableProgress("Reformatting Cargo Project with Rustfmt...") {
                project.toolchain
                    ?.cargoOrWrapper(workingDirectory)
                    ?.toGeneralCommandLine(project, commandLine)
                    ?.execute(owner, false)
            }
        }
    }

    companion object {
        const val NAME: String = "rustfmt"

        private val CONFIG_FILES: List<String> = listOf("rustfmt.toml", ".rustfmt.toml")

        private fun findConfigPathRecursively(directory: VirtualFile, stopAt: Path): Path? {
            val path = directory.pathAsPath
            if (!path.startsWith(stopAt) || path == stopAt) return null
            if (directory.children.any { it.name in CONFIG_FILES }) return path
            return findConfigPathRecursively(directory.parent, stopAt)
        }

        private fun <T> showRustfmtErrorIfAny(project: Project, action: () -> T): T? =
            try {
                action()
            } catch (e: ExecutionException) {
                val stderr = e.message.orEmpty().substringAfter("stderr : ").trimEnd('\n')
                if (stderr.isNotEmpty()) {
                    project.showBalloon("Rustfmt", stderr, NotificationType.ERROR, RustfmtEditSettingsAction("Show settings..."))
                }
                if (isUnitTestMode) throw e else null
            }
    }
}
