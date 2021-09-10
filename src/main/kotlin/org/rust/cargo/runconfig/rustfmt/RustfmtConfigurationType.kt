/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.rustfmt

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project

object RustfmtConfigurationType : ConfigurationTypeBase(
    "RustfmtConfiguration",
    "Rustfmt",
    "Rustfmt run configuration",
    AllIcons.Actions.RealIntentionBulb
) {
    val factory: RustfmtConfigurationFactory get() = configurationFactories.single() as RustfmtConfigurationFactory

    init {
        addFactory(RustfmtConfigurationFactory(this))
    }
}

class RustfmtConfigurationFactory(type: RustfmtConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = ID

    override fun createTemplateConfiguration(project: Project): RustfmtConfiguration {
        return RustfmtConfiguration(project, "Rustfmt", this)
    }

    companion object {
        const val ID: String = "Rustfmt"
    }
}
