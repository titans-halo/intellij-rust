/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.rustfmt

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.Label
import com.intellij.ui.layout.CCFlags
import com.intellij.ui.layout.LayoutBuilder
import com.intellij.ui.layout.Row
import com.intellij.ui.layout.panel
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.runconfig.ui.RsCommandConfigurationEditor
import org.rust.cargo.toolchain.RustChannel
import org.rust.cargo.toolchain.tools.isRustupAvailable
import javax.swing.JComponent

class RustfmtConfigurationEditor(project: Project)
    : RsCommandConfigurationEditor<RustfmtConfiguration, RawCommandLineEditor>(project) {
    override val command: RawCommandLineEditor = RawCommandLineEditor()

    private val allCargoProjects: List<CargoProject> =
        project.cargoProjects.allProjects.sortedBy { it.presentableName }

    private val channelLabel = Label("C&hannel:")
    private val channel = ComboBox<RustChannel>().apply {
        RustChannel.values()
            .sortedBy { it.index }
            .forEach { addItem(it) }
    }

    private val cargoProject = ComboBox<CargoProject>().apply {
        renderer = SimpleListCellRenderer.create("") { it.presentableName }
        allCargoProjects.forEach { addItem(it) }

        addItemListener {
            setWorkingDirectoryFromSelectedProject()
        }
    }

    private fun setWorkingDirectoryFromSelectedProject() {
        val selectedProject = run {
            val idx = cargoProject.selectedIndex
            if (idx == -1) return
            cargoProject.getItemAt(idx)
        }
        workingDirectory.component.text = selectedProject.workingDirectory.toString()
    }

    private val environmentVariables = EnvironmentVariablesComponent()

    public override fun resetEditorFrom(configuration: RustfmtConfiguration) {
        super.resetEditorFrom(configuration)

        channel.selectedIndex = configuration.channel.index
        environmentVariables.envData = configuration.env

        val vFile = currentWorkingDirectory?.let { LocalFileSystem.getInstance().findFileByIoFile(it.toFile()) }
        if (vFile == null) {
            cargoProject.selectedIndex = -1
        } else {
            val projectForWd = project.cargoProjects.findProjectForFile(vFile)
            cargoProject.selectedIndex = allCargoProjects.indexOf(projectForWd)
        }
    }

    @Throws(ConfigurationException::class)
    public override fun applyEditorTo(configuration: RustfmtConfiguration) {
        super.applyEditorTo(configuration)

        val configChannel = RustChannel.fromIndex(channel.selectedIndex)

        configuration.channel = configChannel
        configuration.env = environmentVariables.envData

        val rustupAvailable = project.toolchain?.isRustupAvailable ?: false
        channel.isEnabled = rustupAvailable || configChannel != RustChannel.DEFAULT
        if (!rustupAvailable && configChannel != RustChannel.DEFAULT) {
            throw ConfigurationException("Channel cannot be set explicitly because rustup is not available")
        }
    }

    fun isModifiedComparingTo(configuration: RustfmtConfiguration): Boolean = when {
        command.text != configuration.command -> true
        currentWorkingDirectory != configuration.workingDirectory -> true
        RustChannel.fromIndex(channel.selectedIndex) != configuration.channel -> true
        environmentVariables.envData != configuration.env -> true
        else -> false
    }

    public override fun createEditor(): DialogPanel = panel {
        labeledRow("&Additional arguments:", command) {
            command(CCFlags.pushX, CCFlags.growX)
            channelLabel.labelFor = channel
            channelLabel()
            channel()
        }

        row(environmentVariables.label) {
            environmentVariables(growX)
        }

        row(workingDirectory.label) {
            workingDirectory(growX)
            if (project.cargoProjects.allProjects.size > 1) {
                cargoProject(growX)
            }
        }
    }

    private fun LayoutBuilder.labeledRow(labelText: String, component: JComponent, init: Row.() -> Unit) {
        val label = Label(labelText)
        label.labelFor = component
        row(label) { init() }
    }
}
