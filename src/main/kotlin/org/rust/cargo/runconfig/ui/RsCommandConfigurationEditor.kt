/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.ui

import com.intellij.execution.ExecutionBundle
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.TextAccessor
import com.intellij.util.text.nullize
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.runconfig.RsCommandConfiguration
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent

abstract class RsCommandConfigurationEditor<T, C>(protected val project: Project) : SettingsEditor<T>()
    where T : RsCommandConfiguration, C : TextAccessor, C : JComponent {

    abstract val command: C

    protected fun currentWorkspace(): CargoWorkspace? =
        CargoCommandConfiguration.findCargoProject(project, command.text, currentWorkingDirectory)?.workspace

    protected val currentWorkingDirectory: Path?
        get() = workingDirectory.component.text.nullize()?.let { Paths.get(it) }

    protected val workingDirectory: LabeledComponent<TextFieldWithBrowseButton> =
        WorkingDirectoryComponent()

    override fun resetEditorFrom(configuration: T) {
        command.text = configuration.command
        workingDirectory.component.text = configuration.workingDirectory?.toString().orEmpty()
    }

    override fun applyEditorTo(configuration: T) {
        configuration.command = command.text
        configuration.workingDirectory = currentWorkingDirectory
    }
}

private class WorkingDirectoryComponent : LabeledComponent<TextFieldWithBrowseButton>() {
    init {
        component = TextFieldWithBrowseButton().apply {
            val fileChooser = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                title = ExecutionBundle.message("select.working.directory.message")
            }
            addBrowseFolderListener(null, null, null, fileChooser)
        }
        text = ExecutionBundle.message("run.configuration.working.directory.label")
    }
}
