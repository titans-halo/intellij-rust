/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.util.InspectionMessage
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.rust.ide.annotator.fixes.AddTypeFix
import org.rust.ide.inspections.fixes.SubstituteTextFix
import org.rust.lang.core.C_VARIADIC
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.type
import org.rust.lang.utils.RsDiagnostic
import org.rust.lang.utils.addToHolder
import org.rust.openapiext.forEachChild
import org.rust.stdext.pluralize
import java.lang.Integer.max

class RsSyntaxErrorsAnnotator : AnnotatorBase() {
    override fun annotateInternal(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is RsExternAbi -> checkExternAbi(holder, element)
            is RsItemElement -> {
                checkItem(holder, element)
                when (element) {
                    is RsFunction -> checkFunction(holder, element)
                    is RsStructItem -> checkStructItem(holder, element)
                    is RsTypeAlias -> checkTypeAlias(holder, element)
                    is RsConstant -> checkConstant(holder, element)
                    is RsModItem -> checkModItem(holder, element)
                    is RsModDeclItem -> checkModDeclItem(holder, element)
                    is RsForeignModItem -> checkForeignModItem(holder, element)
                }
            }
            is RsMacro -> checkMacro(holder, element)
            is RsMacroCall -> checkMacroCall(holder, element)
            is RsValueParameterList -> checkValueParameterList(holder, element)
            is RsValueParameter -> checkValueParameter(holder, element)
            is RsTypeParameterList -> checkTypeParameterList(holder, element)
            is RsTypeArgumentList -> checkTypeArgumentList(holder, element)
        }
    }
}

private fun checkItem(holder: AnnotationHolder, item: RsItemElement) {
    checkItemOrMacro(item, item.itemKindName.pluralize().capitalize(), item.itemDefKeyword, holder)
}

private fun checkMacro(holder: AnnotationHolder, element: RsMacro) =
    checkItemOrMacro(element, "Macros", element.macroRules, holder)

private fun checkItemOrMacro(item: RsElement, itemName: String, highlightElement: PsiElement, holder: AnnotationHolder) {
    if (item !is RsAbstractable) {
        val parent = item.context
        val owner = if (parent is RsMembers) parent.context else parent
        if (owner is RsItemElement && (owner is RsForeignModItem || owner is RsTraitOrImpl)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "$itemName are not allowed inside ${owner.article} ${owner.itemKindName}")
                .range(highlightElement).create()
        }
    }

    if (item !is RsAbstractable && item !is RsTraitOrImpl) {
        denyDefaultKeyword(item, holder, itemName)
    }
}

private fun denyDefaultKeyword(item: RsElement, holder: AnnotationHolder, itemName: String) {
    deny(
        item.node.findChildByType(RsElementTypes.DEFAULT)?.psi,
        holder,
        "$itemName cannot have the `default` qualifier"
    )
}

private fun checkMacroCall(holder: AnnotationHolder, element: RsMacroCall) {
    denyDefaultKeyword(element, holder, "Macro invocations")
}

private fun checkFunction(holder: AnnotationHolder, fn: RsFunction) {
    when (fn.owner) {
        is RsAbstractableOwner.Free -> {
            require(fn.block, holder, "${fn.title} must have a body", fn.lastChild)
            deny(fn.default, holder, "${fn.title} cannot have the `default` qualifier")
        }
        is RsAbstractableOwner.Trait -> {
            deny(fn.default, holder, "${fn.title} cannot have the `default` qualifier")
            deny(fn.vis, holder, "${fn.title} cannot have the `pub` qualifier")
            fn.const?.let { RsDiagnostic.ConstTraitFnError(it).addToHolder(holder) }
        }
        is RsAbstractableOwner.Impl -> {
            require(fn.block, holder, "${fn.title} must have a body", fn.lastChild)
            if (fn.default != null) {
                deny(fn.vis, holder, "Default ${fn.title.firstLower} cannot have the `pub` qualifier")
            }
        }
        is RsAbstractableOwner.Foreign -> {
            deny(fn.default, holder, "${fn.title} cannot have the `default` qualifier")
            deny(fn.block, holder, "${fn.title} cannot have a body")
            deny(fn.const, holder, "${fn.title} cannot have the `const` qualifier")
            deny(fn.unsafe, holder, "${fn.title} cannot have the `unsafe` qualifier")
            deny(fn.externAbi, holder, "${fn.title} cannot have an extern ABI")
        }
    }
}

private fun checkStructItem(holder: AnnotationHolder, struct: RsStructItem) {
    if (struct.kind == RsStructKind.UNION && struct.tupleFields != null) {
        deny(struct.tupleFields, holder, "Union cannot be tuple-like")
    }
}

private fun checkTypeAlias(holder: AnnotationHolder, ta: RsTypeAlias) {
    val title = "Type `${ta.identifier.text}`"
    when (val owner = ta.owner) {
        is RsAbstractableOwner.Free -> {
            deny(ta.default, holder, "$title cannot have the `default` qualifier")
            deny(ta.typeParamBounds, holder, "Bounds on $title have no effect")
            require(ta.typeReference, holder, "$title should have a body`", ta)
        }
        is RsAbstractableOwner.Trait -> {
            deny(ta.default, holder, "$title cannot have the `default` qualifier")
        }
        is RsAbstractableOwner.Impl -> {
            if (owner.isInherent) {
                deny(ta.default, holder, "$title cannot have the `default` qualifier")
            }
            deny(ta.typeParamBounds, holder, "Bounds on $title have no effect")
            require(ta.typeReference, holder, "$title should have a body", ta)
        }
        RsAbstractableOwner.Foreign -> {
            deny(ta.default, holder, "$title cannot have the `default` qualifier")
            deny(ta.typeParameterList, holder, "$title cannot have generic parameters")
            deny(ta.whereClause, holder, "$title cannot have `where` clause")
            deny(ta.typeParamBounds, holder, "Bounds on $title have no effect")
            deny(ta.typeReference, holder, "$title cannot have a body", ta)
        }
    }
}

private fun checkConstant(holder: AnnotationHolder, const: RsConstant) {
    val name = const.nameLikeElement.text
    val title = if (const.static != null) "Static constant `$name`" else "Constant `$name`"
    when (const.owner) {
        is RsAbstractableOwner.Free -> {
            deny(const.default, holder, "$title cannot have the `default` qualifier")
            require(const.expr, holder, "$title must have a value", const)
        }
        is RsAbstractableOwner.Foreign -> {
            deny(const.default, holder, "$title cannot have the `default` qualifier")
            require(const.static, holder, "Only static constants are allowed in extern blocks", const.const)
            deny(const.expr, holder, "Static constants in extern blocks cannot have values", const.eq, const.expr)
        }
        is RsAbstractableOwner.Trait -> {
            deny(const.vis, holder, "$title cannot have the `pub` qualifier")
            deny(const.default, holder, "$title cannot have the `default` qualifier")
            deny(const.static, holder, "Static constants are not allowed in traits")
        }
        is RsAbstractableOwner.Impl -> {
            deny(const.static, holder, "Static constants are not allowed in impl blocks")
            require(const.expr, holder, "$title must have a value", const)
        }
    }
    checkConstantType(holder, const)
}

private fun checkConstantType(holder: AnnotationHolder, element: RsConstant) {
    if (element.colon == null && element.typeReference == null) {
        val typeText = if (element.isConst) {
            "const"
        } else {
            "static"
        }
        val message = "Missing type for `$typeText` item"

        val annotation = holder.newAnnotation(HighlightSeverity.ERROR, message)
            .range(element.textRange)

        val expr = element.expr
        if (expr != null) {
            annotation.withFix(AddTypeFix(element.nameLikeElement, expr.type))
        }

        annotation.create()
    }
}

private fun checkValueParameterList(holder: AnnotationHolder, params: RsValueParameterList) {
    val fn = params.parent as? RsFunction ?: return
    when (fn.owner) {
        is RsAbstractableOwner.Free -> {
            deny(params.selfParameter, holder, "${fn.title} cannot have `self` parameter")
            checkVariadic(holder, fn, params.variadic?.dotdotdot)
        }
        is RsAbstractableOwner.Trait, is RsAbstractableOwner.Impl -> {
            deny(params.variadic?.dotdotdot, holder, "${fn.title} cannot be variadic")
        }
        RsAbstractableOwner.Foreign -> {
            deny(params.selfParameter, holder, "${fn.title} cannot have `self` parameter")
            checkDot3Parameter(holder, params.variadic?.dotdotdot)
        }
    }
}

private fun checkVariadic(holder: AnnotationHolder, fn: RsFunction, dot3: PsiElement?) {
    if (dot3 == null) return
    if (fn.isUnsafe && fn.abiName == "C") {
        C_VARIADIC.check(holder, dot3, "C-variadic functions")
    } else {
        deny(dot3, holder, "${fn.title} cannot be variadic")
    }
}

private fun checkDot3Parameter(holder: AnnotationHolder, dot3: PsiElement?) {
    if (dot3 == null) return
    dot3.rightVisibleLeaves
        .first {
            if (it.text != ")") {
                holder.newAnnotation(HighlightSeverity.ERROR, "`...` must be last in argument list for variadic function")
                    .range(it).create()
            }
            return
        }
}

private fun checkValueParameter(holder: AnnotationHolder, param: RsValueParameter) {
    val fn = param.parent.parent as? RsFunction ?: return
    when (fn.owner) {
        is RsAbstractableOwner.Free,
        is RsAbstractableOwner.Impl,
        is RsAbstractableOwner.Foreign -> {
            require(param.pat, holder, "${fn.title} cannot have anonymous parameters", param)
        }
        is RsAbstractableOwner.Trait -> {
            denyType<RsPatTup>(param.pat, holder, "${fn.title} cannot have tuple parameters", param)
            if (param.pat == null) {
                val message = "Anonymous functions parameters are deprecated (RFC 1685)"
                val annotation = holder.newAnnotation(HighlightSeverity.WARNING, message)

                val fix = SubstituteTextFix.replace(
                    "Add dummy parameter name",
                    param.containingFile,
                    param.textRange,
                    "_: ${param.text}"
                )
                val descriptor = InspectionManager.getInstance(param.project)
                    .createProblemDescriptor(param, message, fix, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, true)

                annotation.newLocalQuickFix(fix, descriptor).registerFix().create()
            }
        }
    }
}

private fun checkTypeParameterList(holder: AnnotationHolder, element: RsTypeParameterList) {
    if (element.parent is RsImplItem || element.parent is RsFunction) {
        element.typeParameterList
            .mapNotNull { it.typeReference }
            .forEach {
                holder.newAnnotation(
                    HighlightSeverity.ERROR,
                    "Defaults for type parameters are only allowed in `struct`, `enum`, `type`, or `trait` definitions"
                ).range(it).create()
            }
    } else {
        val lastNotDefaultIndex = max(element.typeParameterList.indexOfLast { it.typeReference == null }, 0)
        element.typeParameterList
            .take(lastNotDefaultIndex)
            .filter { it.typeReference != null }
            .forEach {
                holder.newAnnotation(HighlightSeverity.ERROR, "Type parameters with a default must be trailing")
                    .range(it).create()
            }
    }

    checkTypeList(element, "parameters", holder)
}

private fun checkTypeArgumentList(holder: AnnotationHolder, args: RsTypeArgumentList) {
    checkTypeList(args, "arguments", holder)

    val startOfAssocTypeBindings = args.assocTypeBindingList.firstOrNull()?.textOffset ?: return
    for (generic in args.lifetimeList + args.typeReferenceList + args.exprList) {
        if (generic.textOffset > startOfAssocTypeBindings) {
            holder.newAnnotation(HighlightSeverity.ERROR, "Generic arguments must come before the first constraint")
                .range(generic).create()
        }
    }
}

private fun checkTypeList(typeList: PsiElement, elementsName: String, holder: AnnotationHolder) {
    var kind = TypeKind.LIFETIME
    typeList.forEachChild { child ->
        val newKind = TypeKind.forType(child) ?: return@forEachChild
        if (newKind.canStandAfter(kind)) {
            kind = newKind
        } else {
            val newStateName = newKind.presentableName.capitalize()
            holder.newAnnotation(
                HighlightSeverity.ERROR,
                "$newStateName $elementsName must be declared prior to ${kind.presentableName} $elementsName"
            ).range(child).create()
        }
    }
}

private fun checkExternAbi(holder: AnnotationHolder, element: RsExternAbi) {
    val litExpr = element.litExpr ?: return
    val abyLiteralKind = litExpr.kind ?: return
    if (abyLiteralKind !is RsLiteralKind.String) {
        holder.newAnnotation(HighlightSeverity.ERROR, "Non-string ABI literal").range(litExpr).create()
    }
}

private fun checkModDeclItem(holder: AnnotationHolder, element: RsModDeclItem) {
    checkInvalidUnsafe(holder, element.unsafe, "Module")
}

private fun checkModItem(holder: AnnotationHolder, element: RsModItem) {
    checkInvalidUnsafe(holder, element.unsafe, "Module")
}

private fun checkForeignModItem(holder: AnnotationHolder, element: RsForeignModItem) {
    checkInvalidUnsafe(holder, element.unsafe, "Extern block")
}

private fun checkInvalidUnsafe(holder: AnnotationHolder, unsafe: PsiElement?, itemName: String) {
    if (unsafe != null) {
        holder.newAnnotation(HighlightSeverity.ERROR, "$itemName cannot be declared unsafe").range(unsafe).create()
    }
}

private enum class TypeKind {
    LIFETIME,
    TYPE,
    CONST;

    val presentableName: String get() = name.toLowerCase()

    fun canStandAfter(prev: TypeKind): Boolean = this !== LIFETIME || prev === LIFETIME

    companion object {
        fun forType(seekingElement: PsiElement): TypeKind? =
            when (seekingElement) {
                is RsLifetimeParameter, is RsLifetime -> LIFETIME
                is RsTypeParameter, is RsTypeReference -> TYPE
                is RsConstParameter, is RsExpr -> CONST
                else -> null
            }
    }
}

private fun require(el: PsiElement?, holder: AnnotationHolder, message: String, vararg highlightElements: PsiElement?): Unit? =
    if (el != null || highlightElements.combinedRange == null) null
    else {
        holder.newAnnotation(HighlightSeverity.ERROR, message)
            .range(highlightElements.combinedRange!!).create()
    }

private fun deny(
    el: PsiElement?,
    holder: AnnotationHolder,
    @InspectionMessage message: String,
    vararg highlightElements: PsiElement?
) {
    if (el == null) return
    holder.newAnnotation(HighlightSeverity.ERROR, message)
        .range(highlightElements.combinedRange ?: el.textRange).create()
}

private inline fun <reified T : RsElement> denyType(
    el: PsiElement?,
    holder: AnnotationHolder,
    @InspectionMessage message: String,
    vararg highlightElements: PsiElement?
) {
    if (el !is T) return
    holder.newAnnotation(HighlightSeverity.ERROR, message)
        .range(highlightElements.combinedRange ?: el.textRange).create()
}

private val Array<out PsiElement?>.combinedRange: TextRange?
    get() = if (isEmpty())
        null
    else filterNotNull()
        .map { it.textRange }
        .reduce(TextRange::union)

private val PsiElement.rightVisibleLeaves: Sequence<PsiElement>
    get() = generateSequence(PsiTreeUtil.nextVisibleLeaf(this)) { el -> PsiTreeUtil.nextVisibleLeaf(el) }

private val String.firstLower: String
    get() = if (isEmpty()) this else this[0].toLowerCase() + substring(1)

