/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.messages.Topic
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.runconfig.buildtool.CargoPatch
import org.rust.cargo.runconfig.buildtool.cargoPatches
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.toolchain.impl.RustcVersion
import org.rust.cargo.toolchain.tools.Cargo
import org.rust.cargo.toolchain.tools.cargoOrWrapper
import org.rust.cargo.toolchain.tools.rustc
import java.nio.file.Path

abstract class CargoRunStateBase(
    environment: ExecutionEnvironment,
    val runConfiguration: CargoCommandConfiguration,
    val config: CargoCommandConfiguration.CleanConfiguration.Ok
) : CommandLineState(environment) {
    val toolchain: RsToolchainBase = config.toolchain
    val commandLine: CargoCommandLine = config.cmd
    val cargoProject: CargoProject? = CargoCommandConfiguration.findCargoProject(
        environment.project,
        commandLine.additionalArguments,
        commandLine.workingDirectory
    )
    private val workingDirectory: Path? get() = cargoProject?.workingDirectory

    protected val commandLinePatches: MutableList<CargoPatch> = mutableListOf()

    init {
        commandLinePatches.addAll(environment.cargoPatches)
    }

    fun cargo(): Cargo = toolchain.cargoOrWrapper(workingDirectory)

    fun rustVersion(): RustcVersion? = toolchain.rustc().queryVersion(workingDirectory)

    fun prepareCommandLine(vararg additionalPatches: CargoPatch): CargoCommandLine {
        var commandLine = commandLine
        for (patch in commandLinePatches) {
            commandLine = patch(commandLine)
        }
        for (patch in additionalPatches) {
            commandLine = patch(commandLine)
        }
        return commandLine
    }

    override fun startProcess(): ProcessHandler = startProcess(processColors = true)

    /**
     * @param processColors if true, process ANSI escape sequences, otherwise keep escape codes in the output
     */
    fun startProcess(processColors: Boolean): ProcessHandler {
        val commandLine = cargo().toColoredCommandLine(environment.project, prepareCommandLine())
        LOG.debug("Executing Cargo command: `${commandLine.commandLineString}`")
        val handler = RsProcessHandler(commandLine, processColors)
        environment.project.messageBus.syncPublisher(CARGO_PROCESS_TOPIC).cargoProcessStarted()
        ProcessTerminatedListener.attach(handler) // shows exit code upon termination
        return handler
    }

    fun interface CargoProcessListener {
        fun cargoProcessStarted()
    }

    companion object {
        private val LOG: Logger = logger<CargoRunStateBase>()

        @JvmField
        val CARGO_PROCESS_TOPIC: Topic<CargoProcessListener> = Topic(
            "cargo process changed",
            CargoProcessListener::class.java
        )
    }
}
