/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.rustfmt

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import org.jdom.Element
import org.rust.cargo.runconfig.RsCommandConfiguration
import org.rust.cargo.runconfig.readEnum
import org.rust.cargo.runconfig.writeEnum
import org.rust.cargo.toolchain.RustChannel

class RustfmtConfiguration(
    project: Project,
    name: String,
    factory: ConfigurationFactory
) : RsCommandConfiguration(project, name, factory) {
    override var command: String = ""
    var channel: RustChannel = RustChannel.DEFAULT
    var env: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT

    override fun getConfigurationEditor(): RustfmtConfigurationEditor = RustfmtConfigurationEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.writeEnum("channel", channel)
        env.writeExternal(element)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        element.readEnum<RustChannel>("channel")?.let { channel = it }
        env = EnvironmentVariablesData.readExternal(element)
    }
}
