/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.configurable

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.layout.panel
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.runconfig.rustfmt.RustfmtConfiguration
import org.rust.cargo.runconfig.rustfmt.RustfmtConfigurationEditor

class RustfmtConfigurable(project: Project) : RsConfigurableBase(project, "Rustfmt") {
    private val configuration: RustfmtConfiguration = project.rustSettings.rustfmtConfiguration
    private val configurationEditor: RustfmtConfigurationEditor = configuration.configurationEditor

    override fun createPanel(): DialogPanel = panel {
        blockRow {
            configurationEditor.createEditor()(pushX, growX).apply {
                onApply { configurationEditor.applyEditorTo(configuration) }
                onReset { configurationEditor.resetEditorFrom(configuration) }
                onIsModified { configurationEditor.isModifiedComparingTo(configuration) }
            }
        }
        row { checkBox("Use rustfmt instead of built-in formatter", state::useRustfmt) }
        row { checkBox("Run rustfmt on Save", state::runRustfmtOnSave) }
    }
}
