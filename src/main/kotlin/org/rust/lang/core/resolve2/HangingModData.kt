/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import org.rust.lang.core.macros.RangeMap
import org.rust.lang.core.macros.decl.MACRO_DOLLAR_CRATE_IDENTIFIER
import org.rust.lang.core.macros.decl.MACRO_DOLLAR_CRATE_IDENTIFIER_REGEX
import org.rust.lang.core.macros.findMacroCallExpandedFromNonRecursive
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsMacro
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve2.RsModInfoBase.InfoNotFound
import org.rust.lang.core.resolve2.RsModInfoBase.RsModInfo
import org.rust.lang.core.resolve2.util.DollarCrateHelper
import org.rust.openapiext.getCachedOrCompute

/**
 * Consider code:
 * ```kotlin
 * /// mod scope
 * fn main() {  /// local scope
 *     use mod1::mod2;
 *     if true {  /// nested local scope
 *         use mod2::func;
 *         func();
 *     }
 * }
 * ```
 * Here we have three scopes, module scope and two local scopes.
 * Module scope will have usual [ModData] which are computed beforehand and stored in [DefMapService].
 * Local scope will have hanging [ModData] which is computed on-demand.
 * It will live in "context" of containing mod [ModData].
 * New resolve in local scopes handles only local imports, local are handled using old resolve.
 */
fun getHangingModInfo(scope: RsBlock): RsModInfoBase {
    if (!shouldCreateHangingModInfo(scope)) return InfoNotFound

    val contextInfo = scope.getContextModInfo()
    if (contextInfo !is RsModInfo) return contextInfo

    val (project, defMap, contextData) = contextInfo
    val dependencies = listOf(
        defMap.timestamp,
        contextData.timestamp,
        // TODO fine-grained tracking of block modifications
        scope.containingFile.modificationStamp
    )
    val hangingModData = getCachedOrCompute(scope, HANGING_MOD_DATA_KEY, dependencies) {
        createHangingModData(scope, contextInfo)
    }
    val dataPsiHelper = LocalScopeDataPsiHelper(scope, hangingModData, contextInfo.dataPsiHelper)
    return RsModInfo(project, defMap, hangingModData, contextInfo.crate, dataPsiHelper)
}

private fun createHangingModData(scope: RsBlock, contextInfo: RsModInfo): ModData {
    val (project, defMap, contextData, crate) = contextInfo

    val hangingModData = ModData(
        parent = contextData.parent,
        crate = contextData.crate,
        path = contextData.path.append("#block"),
        /** Affects [resolveMacroCallToLegacyMacroDefInfo] */
        macroIndex = contextData.macroIndex.append(Int.MAX_VALUE),
        isDeeplyEnabledByCfgOuter = contextData.isDeeplyEnabledByCfgOuter,
        isEnabledByCfgInner = scope.isEnabledByCfg(crate),
        fileId = null,
        fileRelativePath = "",
        ownedDirectoryId = null,
        hasPathAttribute = false,
        hasMacroUse = false,
        isNormalCrate = false,
        context = contextData,
        crateDescription = "block in ${contextData.crateDescription}",
    )

    val collectorContext = CollectorContext(crate, project, isHangingMode = true)
    val modCollectorContext = ModCollectorContext(defMap, collectorContext)
    collectScope(scope, hangingModData, modCollectorContext, dollarCrateHelper = createDollarCrateHelper(scope))

    val indicator = ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator()
    DefCollector(project, defMap, collectorContext, pool = null, indicator).collect()
    return hangingModData
}

private val HANGING_MOD_DATA_KEY: Key<Pair<ModData, List<Long>>> = Key.create("HANGING_MOD_DATA_KEY")

private fun RsElement.getContextModInfo(): RsModInfoBase {
    val context = contextStrict<RsItemsOwner>() ?: return InfoNotFound
    return when {
        context is RsMod -> getModInfo(context)
        context is RsBlock && shouldCreateHangingModInfo(context) -> getHangingModInfo(context)
        else -> context.getContextModInfo()
    }
}

private fun shouldCreateHangingModInfo(scope: RsBlock): Boolean =
    scope.itemsAndMacros.any { it is RsItemElement || it is RsMacro || it is RsMacroCall }

private fun createDollarCrateHelper(scope: RsBlock): DollarCrateHelper? {
    val call = scope.findMacroCallExpandedFromNonRecursive() as? RsMacroCall ?: return null
    val expansionText = scope.text
    if (!expansionText.contains(MACRO_DOLLAR_CRATE_IDENTIFIER)) return null

    val defCrate = call.resolveToMacroAndGetContainingCrate()?.id ?: return null
    /** See [RsItemsOwner.getOrBuildStub] */
    val additionalOffset = scope.greenStub?.lbraceOffset ?: BLOCK_STUB_PREFIX.length
    val rangesInExpansion = MACRO_DOLLAR_CRATE_IDENTIFIER_REGEX.findAll(expansionText)
        .associate { it.range.first + additionalOffset to defCrate }
    // TODO: Proper implementation with support of `defHasLocalInnerMacros`, macro expanded to macro, etc
    return DollarCrateHelper(RangeMap.EMPTY, rangesInExpansion, defHasLocalInnerMacros = false, defCrate)
}

private class LocalScopeDataPsiHelper(
    private val scope: RsBlock,
    private val modData: ModData,
    private val delegate: DataPsiHelper?,
) : DataPsiHelper {
    override fun psiToData(scope: RsItemsOwner): ModData? {
        if (scope == this.scope) return modData
        return delegate?.psiToData(scope)
    }

    override fun dataToPsi(data: ModData): RsItemsOwner? {
        if (data == modData) return scope
        return delegate?.dataToPsi(data)
    }

    override fun findModData(path: ModPath): ModData? {
        if (path == modData.path) return modData
        if (modData.path.isSubPathOf(path)) {
            val relativePath = path.segments.copyOfRange(modData.path.segments.size, path.segments.size)
            return modData.getChildModData(relativePath)
        }
        return delegate?.findModData(path)
    }
}
