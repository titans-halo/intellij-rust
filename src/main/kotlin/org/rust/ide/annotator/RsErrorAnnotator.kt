/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.codeInsight.daemon.impl.HighlightRangeExtension
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import org.rust.cargo.project.workspace.CargoWorkspace.Edition
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.annotator.fixes.*
import org.rust.ide.presentation.getStubOnlyText
import org.rust.ide.presentation.shortPresentableText
import org.rust.ide.refactoring.RsNamesValidator.Companion.RESERVED_LIFETIME_NAMES
import org.rust.ide.refactoring.findBinding
import org.rust.lang.core.*
import org.rust.lang.core.FeatureAvailability.*
import org.rust.lang.core.macros.MacroExpansionMode
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.macros.proc.ProcMacroApplicationService
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.IDENTIFIER
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.resolve.ref.deepResolve
import org.rust.lang.core.types.*
import org.rust.lang.core.types.consts.asLong
import org.rust.lang.core.types.infer.containsTyOfClass
import org.rust.lang.core.types.infer.substitute
import org.rust.lang.core.types.ty.*
import org.rust.lang.utils.RsDiagnostic
import org.rust.lang.utils.RsDiagnostic.IncorrectFunctionArgumentCountError.FunctionType
import org.rust.lang.utils.RsErrorCode
import org.rust.lang.utils.SUPPORTED_CALLING_CONVENTIONS
import org.rust.lang.utils.addToHolder
import org.rust.lang.utils.evaluation.evaluate
import org.rust.openapiext.isUnitTestMode

class RsErrorAnnotator : AnnotatorBase(), HighlightRangeExtension {
    override fun isForceHighlightParents(file: PsiFile): Boolean = file is RsFile

    override fun annotateInternal(element: PsiElement, holder: AnnotationHolder) {
        val rsHolder = RsAnnotationHolder(holder)
        val visitor = object : RsVisitor() {
            override fun visitBaseType(o: RsBaseType) = checkBaseType(rsHolder, o)
            override fun visitCondition(o: RsCondition) = checkCondition(rsHolder, o)
            override fun visitConstant(o: RsConstant) = checkConstant(rsHolder, o)
            override fun visitTypeArgumentList(o: RsTypeArgumentList) = checkTypeArgumentList(rsHolder, o)
            override fun visitValueParameterList(o: RsValueParameterList) = checkValueParameterList(rsHolder, o)
            override fun visitValueArgumentList(o: RsValueArgumentList) = checkValueArgumentList(rsHolder, o)
            override fun visitStructItem(o: RsStructItem) = checkDuplicates(rsHolder, o)
            override fun visitEnumItem(o: RsEnumItem) = checkEnumItem(rsHolder, o)
            override fun visitEnumVariant(o: RsEnumVariant) = checkEnumVariant(rsHolder, o)
            override fun visitExternAbi(o: RsExternAbi) = checkExternAbi(rsHolder, o)
            override fun visitFunction(o: RsFunction) = checkFunction(rsHolder, o)
            override fun visitImplItem(o: RsImplItem) = checkImpl(rsHolder, o)
            override fun visitLetDecl(o: RsLetDecl) = checkLetDecl(rsHolder, o)
            override fun visitLetElseBranch(o: RsLetElseBranch) = checkLetElseBranch(rsHolder, o)
            override fun visitLabel(o: RsLabel) = checkLabel(rsHolder, o)
            override fun visitLifetime(o: RsLifetime) = checkLifetime(rsHolder, o)
            override fun visitMatchArmGuard(o: RsMatchArmGuard) = checkMatchArmGuard(rsHolder, o)
            override fun visitModDeclItem(o: RsModDeclItem) = checkModDecl(rsHolder, o)
            override fun visitModItem(o: RsModItem) = checkDuplicates(rsHolder, o)
            override fun visitUseSpeck(o: RsUseSpeck) = checkUseSpeck(rsHolder, o)
            override fun visitPatBox(o: RsPatBox) = checkPatBox(rsHolder, o)
            override fun visitPatField(o: RsPatField) = checkPatField(rsHolder, o)
            override fun visitPatBinding(o: RsPatBinding) = checkPatBinding(rsHolder, o)
            override fun visitPatRest(o: RsPatRest) = checkPatRest(rsHolder, o)
            override fun visitOrPat(o: RsOrPat) = checkOrPat(rsHolder, o)
            override fun visitPath(o: RsPath) = checkPath(rsHolder, o)
            override fun visitNamedFieldDecl(o: RsNamedFieldDecl) = checkDuplicates(rsHolder, o)
            override fun visitRetExpr(o: RsRetExpr) = checkRetExpr(rsHolder, o)
            override fun visitTraitItem(o: RsTraitItem) = checkDuplicates(rsHolder, o)
            override fun visitTypeAlias(o: RsTypeAlias) = checkTypeAlias(rsHolder, o)
            override fun visitTypeParameter(o: RsTypeParameter) = checkDuplicates(rsHolder, o)
            override fun visitConstParameter(o: RsConstParameter) = checkConstParameter(rsHolder, o)
            override fun visitLifetimeParameter(o: RsLifetimeParameter) = checkLifetimeParameter(rsHolder, o)
            override fun visitVis(o: RsVis) = checkVis(rsHolder, o)
            override fun visitVisRestriction(o: RsVisRestriction) = checkVisRestriction(rsHolder, o)
            override fun visitUnaryExpr(o: RsUnaryExpr) = checkUnary(rsHolder, o)
            override fun visitBinaryExpr(o: RsBinaryExpr) = checkBinary(rsHolder, o)
            override fun visitExternCrateItem(o: RsExternCrateItem) = checkExternCrate(rsHolder, o)
            override fun visitDotExpr(o: RsDotExpr) = checkDotExpr(rsHolder, o)
            override fun visitYieldExpr(o: RsYieldExpr) = checkYieldExpr(rsHolder, o)
            override fun visitArrayType(o: RsArrayType) = checkArrayType(rsHolder, o)
            override fun visitArrayExpr(o: RsArrayExpr) = checkArrayExpr(rsHolder, o)
            override fun visitVariantDiscriminant(o: RsVariantDiscriminant) = collectDiagnostics(rsHolder, o)
            override fun visitPolybound(o: RsPolybound) = checkPolybound(rsHolder, o)
            override fun visitTraitRef(o: RsTraitRef) = checkTraitRef(rsHolder, o)
            override fun visitCallExpr(o: RsCallExpr) = checkCallExpr(rsHolder, o)
            override fun visitBlockExpr(o: RsBlockExpr) = checkBlockExpr(rsHolder, o)
            override fun visitBreakExpr(o: RsBreakExpr) = checkBreakExpr(rsHolder, o)
            override fun visitContExpr(o: RsContExpr) = checkContExpr(rsHolder, o)
            override fun visitAttr(o: RsAttr) = checkAttr(rsHolder, o)
            override fun visitRangeExpr(o: RsRangeExpr) = checkRangeExpr(rsHolder, o)
            override fun visitTraitType(o: RsTraitType) = checkTraitType(rsHolder, o)
            override fun visitSelfParameter(o: RsSelfParameter) = checkParamAttrs(rsHolder, o)
            override fun visitValueParameter(o: RsValueParameter) = checkParamAttrs(rsHolder, o)
            override fun visitVariadic(o: RsVariadic) = checkParamAttrs(rsHolder, o)
            override fun visitPatStruct(o: RsPatStruct) = checkRsPatStruct(rsHolder, o)
            override fun visitPatTupleStruct(o: RsPatTupleStruct) = checkRsPatTupleStruct(rsHolder, o)
            override fun visitPatTup(o: RsPatTup) = checkRsPatTup(rsHolder, o)
            override fun visitStructLiteralField(o: RsStructLiteralField) = checkReferenceIsPublic(o, o, rsHolder)
            override fun visitMetaItem(o: RsMetaItem) = checkMetaItem(rsHolder, o)
        }

        element.accept(visitor)
    }

    private fun checkMetaItem(holder: RsAnnotationHolder, metaItem: RsMetaItem) {
        val args = metaItem.metaItemArgs
        val name = metaItem.name
        if (metaItem.isRootMetaItem() && args != null && name in listOf("cfg", "cfg_attr")) {
            val item = args.metaItemList.getOrNull(0) ?: return
            checkCfgPredicate(holder, item)
        }
    }

    private fun checkCfgPredicate(holder: RsAnnotationHolder, item: RsMetaItem) {
        val itemName = item.name ?: return
        val args = item.metaItemArgs ?: return
        when (itemName) {
            "all", "any" -> args.metaItemList.forEach { checkCfgPredicate(holder, it) }
            "not" -> {
                val parameter = args.metaItemList.getOrNull(0) ?: return
                checkCfgPredicate(holder, parameter)
            }
            "version" -> { /* version is currently experimental */ }
            else -> {
                val path = item.path ?: return
                val fixes = NameSuggestionFix.createApplicable(path, itemName, listOf("all", "any", "not"), 1) { name ->
                    RsPsiFactory(path.project).tryCreatePath(name) ?: error("Cannot create path out of $name")
                }
                RsDiagnostic.UnknownCfgPredicate(path, itemName, fixes).addToHolder(
                    holder,
                    checkExistsAfterExpansion = false
                )
            }
        }
    }

    private fun checkRsPatTup(holder: RsAnnotationHolder, pattern: RsPatTup) {
        if (pattern.isTopLevel) {
            checkRepeatedPatIdentifiers(holder, pattern)
        }
    }

    private fun checkOrPat(holder: RsAnnotationHolder, orPat: RsOrPat) {
        val parent = orPat.context

        if (parent is RsPat) {
            val firstChild = orPat.firstChild
            if (firstChild?.elementType == RsElementTypes.OR) {
                holder.createErrorAnnotation(
                    firstChild,
                    "a leading `|` is only allowed in a top-level pattern",
                    RemoveElementFix(firstChild)
                )
            }
        }

        if (parent !is RsCondition && parent !is RsMatchArm) {
            OR_PATTERNS.check(holder, orPat, "or-patterns syntax")
        }

        if (orPat.isTopLevel) {
            orPat.patList.forEach { checkRepeatedPatIdentifiers(holder, it) }
        }
    }

    private fun checkUseSpeck(holder: RsAnnotationHolder, useSpeck: RsUseSpeck) {
        if (useSpeck.isStarImport || useSpeck.useGroup != null) return

        checkDuplicateImport(holder, useSpeck)
        checkReexports(holder, useSpeck)
    }

    private fun checkDuplicateImport(holder: RsAnnotationHolder, useSpeck: RsUseSpeck) {
        val duplicates = holder.currentAnnotationSession.duplicatesByNamespace(useSpeck.containingMod, false)
        if (useSpeck.namespaces.any { useSpeck in duplicates[it].orEmpty() }) {
            val identifier = PsiTreeUtil.getDeepestLast(useSpeck)
            RsDiagnostic.DuplicateImportError(identifier).addToHolder(holder)
        }
    }

    private fun checkReexports(holder: RsAnnotationHolder, useSpeck: RsUseSpeck) {
        val item = useSpeck.ancestorStrict<RsUseItem>() ?: return
        if (!item.isReexport) return

        val path = useSpeck.path ?: return
        val targets = path.reference?.multiResolve()?.filterIsInstance<RsItemElement>() ?: emptyList()

        // targets not accessible from the use element will be handled by E0603
        val visibleTargets = targets.filter { it.isVisibleFrom(useSpeck.containingMod) }
        val invalidTargets = visibleTargets.filter {
            !canBeReexported(item.visibility, it)
        }

        // if at least one item can be re-exported or nothing is invalid, allow the reexport
        if (invalidTargets.size < targets.size || invalidTargets.isEmpty()) return

        val invalidTarget = invalidTargets.first()
        val context = useSpeck.context
        val name = if (path.referenceName == "self" && context is RsUseGroup) {
            (context.context as? RsUseSpeck)?.path?.referenceName ?: path.referenceName
        } else {
            path.referenceName
        } ?: return

        val element = path.referenceNameElement ?: return
        RsDiagnostic.InvalidReexport(element, name, invalidTarget).addToHolder(holder)
    }

    private fun canBeReexported(reexportVisibility: RsVisibility, item: RsItemElement): Boolean {
        val itemVisibility = item.visibility
        if (itemVisibility is RsVisibility.Public) return true

        return when (reexportVisibility) {
            is RsVisibility.Public -> false
            is RsVisibility.Restricted -> {
                val targetMod = if (itemVisibility is RsVisibility.Restricted) {
                    itemVisibility.inMod
                } else {
                    item.containingMod
                }
                targetMod in reexportVisibility.inMod.superMods
            }
            is RsVisibility.Private -> error("unreachable")
        }
    }

    private fun checkRsPatStruct(holder: RsAnnotationHolder, patStruct: RsPatStruct) {
        val declaration = patStruct.path.reference?.deepResolve() as? RsFieldsOwner ?: return
        val declarationFieldNames = declaration.fields.map { it.name }
        val bodyFields = patStruct.patFieldList
        val extraFields = bodyFields.filter { it.kind.fieldName !in declarationFieldNames }

        if (declaration is RsStructItem && declaration.kind == RsStructKind.UNION) {
            extraFields.forEach { RsDiagnostic.ExtraFieldInStructPattern(it, "union").addToHolder(holder) }

            if (bodyFields.isEmpty()) {
                RsDiagnostic.MissingFieldsInUnionPattern(patStruct).addToHolder(holder)
            } else if (bodyFields.size > 1) {
                RsDiagnostic.TooManyFieldsInUnionPattern(patStruct).addToHolder(holder)
            }
        } else {
            extraFields.forEach { RsDiagnostic.ExtraFieldInStructPattern(it, "struct").addToHolder(holder) }

            val bodyFieldNames = bodyFields.map { it.kind.fieldName }
            val missingFields = declaration.fields.filter { it.name !in bodyFieldNames }

            if (missingFields.isNotEmpty() && patStruct.patRest == null) {
                RsDiagnostic.MissingFieldsInStructPattern(patStruct, declaration, missingFields).addToHolder(holder)
            }

            checkRepeatedPatStructFields(holder, patStruct.patFieldList)
        }

        if (patStruct.isTopLevel) {
            checkRepeatedPatIdentifiers(holder, patStruct)
        }
    }

    private fun checkRepeatedPatStructFields(holder: RsAnnotationHolder, pats: List<RsPatField>) {
        val visitedFields = mutableSetOf<String>()
        for (pat in pats) {
            val binding = pat.patBinding
            val fieldFull = pat.patFieldFull

            val name = if (binding != null) {
                binding.identifier.text
            } else if (fieldFull != null) {
                fieldFull.identifier?.text ?: continue
            } else continue

            if (name in visitedFields) {
                RsDiagnostic.RepeatedFieldInStructPattern(pat, name).addToHolder(holder)
            } else {
                visitedFields.add(name)
            }
        }
    }

    private fun checkRepeatedPatIdentifiers(holder: RsAnnotationHolder, topPattern: RsPat) {
        fun check(pattern: RsPat, identifiers: HashSet<String>): HashSet<String> {
            val visitedIdentifiers = identifiers.toMutableSet()
            val visitor = object : RsRecursiveVisitor() {
                override fun visitPatBinding(binding: RsPatBinding) {
                    val resolved = binding.reference.resolve()
                    if (resolved == null || resolved is RsNamedFieldDecl) {
                        val name = binding.identifier.text
                        if (name in visitedIdentifiers) {
                            RsDiagnostic.RepeatedIdentifierInPattern(binding, name).addToHolder(holder)
                        } else {
                            visitedIdentifiers.add(name)
                        }
                    }
                }

                override fun visitOrPat(pattern: RsOrPat) {
                    // assumes that all OR branches have the same set of identifiers
                    val visitedInBranches = mutableSetOf<String>()
                    for (pat in pattern.patList) {
                        visitedInBranches += check(pat, visitedIdentifiers.toHashSet())
                    }
                    visitedIdentifiers += visitedInBranches
                }
            }
            pattern.accept(visitor)
            return visitedIdentifiers.toHashSet()
        }
        check(topPattern, hashSetOf())
    }

    private fun checkRsPatTupleStruct(holder: RsAnnotationHolder, patTupleStruct: RsPatTupleStruct) {
        val declaration = patTupleStruct.path.reference?.deepResolve() as? RsFieldsOwner ?: return

        val declarationFieldsAmount = declaration.fields.size
        // Rest is non-binding, meaning it is accepted even if all fields are already bound
        val bodyFieldsAmount = patTupleStruct.patList.filterNot { it is RsPatRest }.size
        if (bodyFieldsAmount < declarationFieldsAmount && patTupleStruct.patRest == null) {
            RsDiagnostic.MissingFieldsInTuplePattern(
                patTupleStruct,
                declaration,
                declarationFieldsAmount,
                bodyFieldsAmount
            ).addToHolder(holder)
        } else if (bodyFieldsAmount > declarationFieldsAmount) {
            RsDiagnostic.ExtraFieldInTupleStructPattern(patTupleStruct, bodyFieldsAmount, declarationFieldsAmount)
                .addToHolder(holder)
        }

        if (patTupleStruct.isTopLevel) {
            checkRepeatedPatIdentifiers(holder, patTupleStruct)
        }
    }

    private fun checkTraitType(holder: RsAnnotationHolder, traitType: RsTraitType) {
        if (!traitType.isImpl) return
        val invalidContext = traitType
            .ancestors
            .takeWhile { !(it is RsAssocTypeBinding && it.parentOfType<RsTypeQual>() == null) }
            .firstOrNull {
                it !is RsTypeArgumentList && it.parent is RsPath ||
                    it !is RsMembers && it.parent is RsImplItem ||
                    it is RsFnPointerType ||
                    it is RsWhereClause ||
                    it is RsTypeParameterList ||
                    it is RsFieldsOwner ||
                    it is RsForeignModItem ||
                    it is RsRetType && it.parent.ancestorStrict<RsTraitOrImpl>(RsAbstractable::class.java)?.implementedTrait != null
                // type alias and let expr are not included because
                // they are planned to be allowed soon
            }

        if (invalidContext is RsTypeQual) {
            RsDiagnostic.ImplTraitNotAllowedInPathParams(traitType).addToHolder(holder)
        } else if (invalidContext != null) {
            RsDiagnostic.ImplTraitNotAllowedHere(traitType).addToHolder(holder)
        }

        val outerImplOrStop = traitType
            .ancestors
            .drop(1)
            .firstOrNull { (it is RsTraitType && it.isImpl) || it is RsAssocTypeBinding || it is RsExpr }

        if (outerImplOrStop is RsTraitType) {
            RsDiagnostic.NestedImplTraitNotAllowed(traitType).addToHolder(holder)
        }

    }

    private fun checkEnumItem(holder: RsAnnotationHolder, o: RsEnumItem) {
        checkDuplicates(holder, o)
        o.enumBody?.let { checkDuplicateEnumVariants(holder, it) }
        if (!hasReprIntType(o) && hasStructOrTupleEnumVariantWithDiscriminant(o)) {
            RsDiagnostic.ReprIntRequired(o.identifier ?: o.enum).addToHolder(holder)
        }
    }

    private fun hasReprIntType(owner: RsDocAndAttributeOwner): Boolean =
        owner.queryAttributes.reprAttributes
            .mapNotNull { it.metaItemArgs }
            .flatMap { it.metaItemList.asSequence() }
            .mapNotNull { it.name }
            .any { it in TyInteger.NAMES }

    private fun hasStructOrTupleEnumVariantWithDiscriminant(enum: RsEnumItem): Boolean =
        enum.enumBody?.enumVariantList
            ?.filter { it.blockFields != null || it.tupleFields != null }
            ?.mapNotNull { it.variantDiscriminant }
            ?.isEmpty() == false

    private fun checkEnumVariant(holder: RsAnnotationHolder, variant: RsEnumVariant) {
        checkDuplicates(holder, variant)
        val discr = variant.variantDiscriminant ?: return
        if (variant.blockFields != null || variant.tupleFields != null) {
            ARBITRARY_ENUM_DISCRIMINANT.check(holder, discr.expr ?: discr, "discriminant on a non-unit variant")
        }
    }

    private fun checkDuplicateEnumVariants(holder: RsAnnotationHolder, o: RsEnumBody) {
        data class VariantInfo(val variant: RsEnumVariant, val alreadyReported: Boolean)

        var discrCounter = 0L
        val reprType = (o.parent as? RsEnumItem)?.reprType ?: return
        val indexToVariantMap = hashMapOf<Long, VariantInfo>()
        for (variant in o.enumVariantList) {
            val expr = variant.variantDiscriminant?.expr
            val int = if (expr != null) expr.evaluate(reprType).asLong() ?: return else null
            val idx = int ?: discrCounter
            discrCounter = idx + 1

            val previous = indexToVariantMap[idx]
            if (previous != null) {
                RsDiagnostic.DuplicateEnumDiscriminant(variant, idx).addToHolder(holder)
                if (!previous.alreadyReported) {
                    RsDiagnostic.DuplicateEnumDiscriminant(previous.variant, idx).addToHolder(holder)
                    indexToVariantMap[idx] = previous.copy(alreadyReported = true)
                }
            } else {
                indexToVariantMap[idx] = VariantInfo(variant, alreadyReported = false)
            }
        }
    }

    private fun checkCallExpr(holder: RsAnnotationHolder, o: RsCallExpr) {
        val path = (o.expr as? RsPathExpr)?.path ?: return
        checkNotCallingDrop(o, holder)
        val owner = path.reference?.deepResolve() as? RsFieldsOwner ?: return
        if (owner.tupleFields == null && !owner.implLookup.isAnyFn(owner.asTy())) {
            RsDiagnostic.ExpectedFunction(o).addToHolder(holder)
        }
    }

    private fun checkTraitRef(holder: RsAnnotationHolder, o: RsTraitRef) {
        val item = o.path.reference?.resolve() as? RsItemElement ?: return
        if (item !is RsTraitItem && item !is RsTraitAlias) {
            RsDiagnostic.NotTraitError(o, item).addToHolder(holder)
        }
    }

    private fun checkDotExpr(holder: RsAnnotationHolder, o: RsDotExpr) {
        val field = o.fieldLookup ?: o.methodCall ?: return
        checkReferenceIsPublic(field, o, holder)
        checkUnstableAttribute(field, holder)
        if (field is RsMethodCall) {
            checkNotCallingDrop(field, holder)
        }
    }

    private fun checkYieldExpr(holder: RsAnnotationHolder, o: RsYieldExpr) {
        GENERATORS.check(holder, o.yield, "`yield` syntax")
    }

    // E0040: Explicit destructor call (call to Drop::drop() method on an instance explicitly)
    private fun checkNotCallingDrop(call: RsElement, holder: RsAnnotationHolder) {
        val (ref, identifier) = when (call) {
            is RsCallExpr -> (call.expr as? RsPathExpr)?.path?.reference?.resolve() to call.expr
            is RsMethodCall -> call.reference.resolve() to call.identifier
            else -> null to null
        }
        if ((ref as? RsFunction)?.name != "drop") return

        val trait = when (val owner = ref.owner) {
            // core::ops::drop::Drop::drop(x)
            is RsAbstractableOwner.Trait -> owner.trait
            // Foo::drop(x), x.drop()
            is RsAbstractableOwner.Impl -> owner.impl.traitRef?.resolveToTrait()
            else -> null
        } ?: return

        if (trait == trait.knownItems.Drop) {
            RsDiagnostic.ExplicitCallToDrop(identifier ?: call).addToHolder(holder)
        }
    }

    private fun checkReferenceIsPublic(ref: RsReferenceElement, o: RsElement, holder: RsAnnotationHolder) {
        val reference = ref.reference ?: return
        val highlightedElement = ref.referenceNameElement ?: return
        val referenceName = ref.referenceName ?: return
        val resolvedElement = when (ref) {
            is RsStructLiteralField -> reference.multiResolve().firstOrNull { it is RsVisible }
            else -> reference.resolve()
        } as? RsVisible ?: return
        val oMod = o.contextStrict<RsMod>() ?: return
        if (resolvedElement.isVisibleFrom(oMod)) return
        val withinOneCrate = resolvedElement.crateRoot == o.crateRoot
        val element = when (resolvedElement) {
            is RsVisibilityOwner -> resolvedElement
            is RsFile -> resolvedElement.declaration
            else -> null
        } ?: return

        val error = when {
            element is RsNamedFieldDecl -> {
                val structName = element.ancestorStrict<RsStructItem>()?.crateRelativePath?.removePrefix("::") ?: ""
                RsDiagnostic.StructFieldAccessError(
                    highlightedElement, referenceName, structName,
                    MakePublicFix.createIfCompatible(element, element.name, withinOneCrate)
                )
            }
            ref is RsMethodCall -> RsDiagnostic.AccessError(
                highlightedElement, RsErrorCode.E0624, "Method",
                MakePublicFix.createIfCompatible(element, referenceName, withinOneCrate)
            )
            else -> {
                val itemType = when (element) {
                    is RsItemElement -> element.itemKindName.capitalize()
                    else -> "Item"
                }

                RsDiagnostic.AccessError(
                    highlightedElement, RsErrorCode.E0603, itemType,
                    MakePublicFix.createIfCompatible(element, referenceName, withinOneCrate)
                )
            }
        }
        error.addToHolder(holder)
    }

    private fun checkUnstableAttribute(ref: RsReferenceElement, holder: RsAnnotationHolder) {
        val startElement = ref.referenceNameElement?.takeIf { it.elementType == IDENTIFIER } ?: return
        if (ref.containingCrate?.origin == PackageOrigin.STDLIB) return
        val element = ref.reference?.resolve() as? RsOuterAttributeOwner ?: return
        for (attr in element.queryAttributes.unstableAttributes) {
            val metaItems = attr.metaItemArgs?.metaItemList ?: continue
            val featureName = metaItems.singleOrNull { it.name == "feature" }?.value ?: continue
            val reason = metaItems.singleOrNull { it.name == "reason" }?.value
            val reasonSuffix = if (reason != null) ": $reason" else ""
            val feature = CompilerFeature.find(featureName)
                ?: CompilerFeature(featureName, FeatureState.ACTIVE, null, cache = false)
            feature.check(holder, startElement, null, "`$featureName` is unstable$reasonSuffix")
        }
    }

    private fun checkBaseType(holder: RsAnnotationHolder, type: RsBaseType) {
        if (type.underscore == null) return
        val owner = type.owner.parent
        val ownerParent = owner.parent
        val ownerGrandParent = ownerParent.parent
        if ((owner is RsValueParameter && ownerGrandParent is RsFunction)
            || (owner is RsRetType && ownerParent is RsFunction)
            || owner is RsConstant
            || (owner is RsFieldDecl && (ownerGrandParent is RsStructItem || ownerGrandParent is RsEnumVariant))
        ) {
            RsDiagnostic.TypePlaceholderForbiddenError(type).addToHolder(holder)
        }
    }

    private fun checkPatBox(holder: RsAnnotationHolder, box: RsPatBox) {
        BOX_PATTERNS.check(holder, box.box, "`box` pattern syntax")
    }

    private fun checkPatField(holder: RsAnnotationHolder, field: RsPatField) {
        val box = field.box ?: return
        BOX_PATTERNS.check(holder, box, "`box` pattern syntax")
    }

    private fun checkPatBinding(holder: RsAnnotationHolder, binding: RsPatBinding) {
        binding.ancestorStrict<RsValueParameterList>()?.let { checkDuplicates(holder, binding, it, recursively = true) }
    }

    private fun checkPatRest(holder: RsAnnotationHolder, patRest: RsPatRest) {
        val parent = patRest.parent
        if (parent is RsPatSlice || parent is RsPatIdent && parent.parent is RsPatSlice) {
            SLICE_PATTERNS.check(holder, patRest, "subslice patterns")
        }
    }

    private fun checkPath(holder: RsAnnotationHolder, path: RsPath) {
        val qualifier = path.path
        if ((qualifier == null || isValidSelfSuperPrefix(qualifier)) && !isValidSelfSuperPrefix(path)) {
            val element = path.referenceNameElement ?: return
            holder.createErrorAnnotation(
                element,
                "Invalid path: self and super are allowed only at the beginning"
            )
            return
        }

        val parent = path.parent
        if (path.self != null && parent !is RsPath && parent !is RsUseSpeck && parent !is RsVisRestriction) {
            val function = path.ancestorStrict<RsFunction>()
            if (function == null) {
                holder.createErrorAnnotation(path, "self value is not available in this context")
                return
            }

            if (function.selfParameter == null) {
                RsDiagnostic.SelfInStaticMethodError(path, function).addToHolder(holder)
            }
        }

        val crate = path.crate
        val useSpeck = path.ancestorStrict<RsUseSpeck>()
        val edition = path.edition

        // `pub(crate)` should be annotated
        if (crate != null && (qualifier != null || path.ancestorStrict<RsVisRestriction>() == null)) {
            if (qualifier != null || useSpeck != null && useSpeck.qualifier != null) {
                RsDiagnostic.UndeclaredTypeOrModule(crate).addToHolder(holder)
            } else if (edition == Edition.EDITION_2015) {
                CRATE_IN_PATHS.check(holder, crate, "`crate` in paths")
            }
        }

        checkReferenceIsPublic(path, path, holder)
        checkUnstableAttribute(path, holder)
    }

    private fun checkConstParameter(holder: RsAnnotationHolder, constParameter: RsConstParameter) {
        collectDiagnostics(holder, constParameter)
        checkConstGenerics(holder, constParameter)
        checkDuplicates(holder, constParameter)
    }

    private fun checkLifetimeParameter(holder: RsAnnotationHolder, lifetimeParameter: RsLifetimeParameter) {
        if (lifetimeParameter.name.isIllegalLifetimeName(lifetimeParameter.edition)) {
            RsDiagnostic.IllegalLifetimeName(lifetimeParameter).addToHolder(holder)
        }

        checkReservedLifetimeName(holder, lifetimeParameter)
        checkDuplicates(holder, lifetimeParameter)
    }

    private fun checkReservedLifetimeName(holder: RsAnnotationHolder, lifetimeParameter: RsLifetimeParameter) {
        val lifetimeName = lifetimeParameter.quoteIdentifier.text
        if (lifetimeName in RESERVED_LIFETIME_NAMES) {
            RsDiagnostic.ReservedLifetimeNameError(lifetimeParameter, lifetimeName).addToHolder(holder)
        }
    }

    private fun checkVis(holder: RsAnnotationHolder, vis: RsVis) {
        val parent = vis.parent
        if (parent is RsImplItem ||
            parent is RsForeignModItem ||
            parent is RsEnumVariant ||
            isInTrait(vis) ||
            isInTraitImpl(vis) ||
            isInEnumVariantField(vis)
        ) {
            RsDiagnostic.UnnecessaryVisibilityQualifierError(vis).addToHolder(holder)
        }
        checkCrateVisibilityModifier(holder, vis)
    }

    private fun checkCrateVisibilityModifier(holder: RsAnnotationHolder, vis: RsVis) {
        val crateModifier = vis.crate ?: return
        CRATE_VISIBILITY_MODIFIER.check(holder, crateModifier, "`crate` visibility modifier")
    }

    private fun checkVisRestriction(holder: RsAnnotationHolder, visRestriction: RsVisRestriction) {
        val path = visRestriction.path
        // pub(foo) or pub(super::bar)
        if (visRestriction.`in` == null && (path.path != null || path.kind == PathKind.IDENTIFIER)) {
            RsDiagnostic.IncorrectVisibilityRestriction(visRestriction).addToHolder(holder)
        }
    }

    private fun checkLetDecl(holder: RsAnnotationHolder, letDecl: RsLetDecl) {
        val pat = letDecl.pat
        if (letDecl.letElseBranch != null && pat != null && pat.isIrrefutable) {
            IRREFUTABLE_LET_PATTERNS.check(holder, pat, "irrefutable let pattern")
        }
    }

    private fun checkLetElseBranch(holder: RsAnnotationHolder, elseBranch: RsLetElseBranch) {
        LET_ELSE.check(holder, elseBranch, "let else")
    }

    private fun checkLabel(holder: RsAnnotationHolder, label: RsLabel) {
        if (!hasResolve(label)) return
        RsDiagnostic.UndeclaredLabelError(label).addToHolder(holder)
    }

    private fun checkLifetime(holder: RsAnnotationHolder, lifetime: RsLifetime) {
        if (lifetime.name.isIllegalLifetimeName(lifetime.edition)) {
            RsDiagnostic.IllegalLifetimeName(lifetime).addToHolder(holder)
        }

        if (lifetime.isPredefined || !hasResolve(lifetime)) return

        val owner = lifetime.ancestorStrict<RsGenericDeclaration>() ?: return
        val declarationParts = listOfNotNull(
            (owner as? RsImplItem)?.traitRef,
            (owner as? RsImplItem)?.typeReference,
            (owner as? RsImplItem)?.whereClause,

            (owner as? RsFunction)?.typeParameterList,
            (owner as? RsFunction)?.valueParameterList,
            (owner as? RsFunction)?.whereClause
        )
        val inDeclaration = lifetime.ancestors.takeWhile { it != owner }.any { it in declarationParts }

        when {
            inDeclaration && owner.lifetimeParameters.isEmpty() -> {
                val fixes = listOfNotNull(CreateLifetimeParameterFromUsageFix.tryCreate(lifetime)).toTypedArray()
                IN_BAND_LIFETIMES.check(holder, lifetime, "in-band lifetimes", *fixes)
            }
            inDeclaration && IN_BAND_LIFETIMES.availability(lifetime) == AVAILABLE ->
                RsDiagnostic.InBandAndExplicitLifetimesError(lifetime).addToHolder(holder)
            else ->
                RsDiagnostic.UndeclaredLifetimeError(lifetime).addToHolder(holder)
        }
    }

    private fun checkMatchArmGuard(holder: RsAnnotationHolder, guard: RsMatchArmGuard) {
        val let = guard.let
        if (let != null) {
            IF_LET_GUARD.check(holder, let, "if let guard")
        }
    }

    private fun checkModDecl(holder: RsAnnotationHolder, modDecl: RsModDeclItem) {
        checkDuplicates(holder, modDecl)
        val pathAttribute = modDecl.pathAttribute

        // mods inside blocks require explicit path attribute
        // https://github.com/rust-lang/rust/pull/31534
        if (modDecl.isLocal && pathAttribute == null) {
            val message = "Cannot declare a non-inline module inside a block unless it has a path attribute"
            holder.createErrorAnnotation(modDecl, message)
            return
        }

        if (!modDecl.containingMod.ownsDirectory && pathAttribute == null) {
            val featureAvailability = NON_MODRS_MODS.availability(modDecl)
            if (featureAvailability == NOT_AVAILABLE || featureAvailability == CAN_BE_ADDED) {
                // We don't want to show the warning if there is no cargo project
                // associated with the current module. Without it we can't know for
                // sure that a mod is not a directory owner.
                if (modDecl.cargoWorkspace != null) {
                    val addModule = AddModuleFileFix.createFixes(modDecl, expandModuleFirst = true)
                        .toTypedArray()
                    NON_MODRS_MODS.check(
                        holder,
                        modDecl,
                        "mod statements in non-mod.rs files",
                        *addModule
                    )
                }
                return
            }
        }

        if (modDecl.reference.resolve() == null && modDecl.semicolon != null) {
            RsDiagnostic.ModuleNotFound(modDecl).addToHolder(holder)
        }
    }

    private fun checkImpl(holder: RsAnnotationHolder, impl: RsImplItem) {
        checkImplForNonAdtError(holder, impl)
        checkConstTraitImpl(holder, impl)
        checkInherentImplSameCrate(holder, impl)
        val traitRef = impl.traitRef ?: return
        val trait = traitRef.resolveToTrait() ?: return
        checkForbiddenImpl(holder, traitRef, trait)
        checkImplDropForNonAdtError(holder, impl, traitRef, trait)
        checkSuperTraitImplemented(holder, impl, trait)
        checkImplBothCopyAndDrop(holder, impl, trait)
        checkTraitImplOrphanRules(holder, impl)
        val traitName = trait.name ?: return

        fun mayDangleOnTypeOrLifetimeParameters(impl: RsImplItem): Boolean {
            return impl.typeParameters.any { it.queryAttributes.hasAtomAttribute("may_dangle") } ||
                impl.lifetimeParameters.any { it.queryAttributes.hasAtomAttribute("may_dangle") }
        }

        val attrRequiringUnsafeImpl = if (mayDangleOnTypeOrLifetimeParameters(impl)) "may_dangle" else null
        when {
            impl.isUnsafe && impl.excl != null ->
                RsDiagnostic.UnsafeNegativeImplementationError(traitRef).addToHolder(holder)

            impl.isUnsafe && !trait.isUnsafe && attrRequiringUnsafeImpl == null ->
                RsDiagnostic.UnsafeTraitImplError(traitRef, traitName).addToHolder(holder)

            !impl.isUnsafe && trait.isUnsafe && impl.excl == null ->
                RsDiagnostic.TraitMissingUnsafeImplError(traitRef, traitName).addToHolder(holder)

            !impl.isUnsafe && !trait.isUnsafe && impl.excl == null && attrRequiringUnsafeImpl != null ->
                RsDiagnostic.TraitMissingUnsafeImplAttributeError(traitRef, attrRequiringUnsafeImpl).addToHolder(holder)
        }
    }

    private fun checkSuperTraitImplemented(holder: RsAnnotationHolder, impl: RsImplItem, trait: RsTraitItem) {
        if (!isUnitTestMode && impl.project.macroExpansionManager.macroExpansionMode !is MacroExpansionMode.New) return

        val traitRef = impl.traitRef ?: return
        val typeRef = impl.typeReference ?: return
        val type = typeRef.type
        val supertraits = trait.typeParamBounds?.polyboundList?.mapNotNull { it.bound } ?: return
        val lookup = typeRef.implLookup

        val selfSubst = mapOf(TyTypeParameter.self() to type).toTypeSubst()
        val substitution = (impl.implementedTrait?.subst ?: emptySubstitution).substituteInValues(selfSubst) + selfSubst
        var missing = false

        for (bound in supertraits) {
            val requiredTrait = bound.traitRef ?: continue
            val boundTrait = requiredTrait.resolveToBoundTrait() ?: continue
            val locallyBoundTrait = boundTrait.substitute(substitution)
            if (locallyBoundTrait.containsTyOfClass(TyUnknown::class.java)) continue

            val canSelect = lookup.canSelect(TraitRef(type, locallyBoundTrait))
            if (!canSelect) {
                val missingTrait = requiredTrait.getStubOnlyText(substitution)
                missing = true
                RsDiagnostic.SuperTraitIsNotImplemented(traitRef, type, missingTrait).addToHolder(holder)
            }
        }

        if (missing) {
            // Mark the whole impl with a silent error and a quick fix

            val range = TextRange(
                impl.impl.startOffset,
                typeRef.endOffset
            )
            holder.holder
                .newSilentAnnotation(HighlightSeverity.ERROR)
                .textAttributes(TextAttributesKey.createTextAttributesKey("DEFAULT_TEXT_ATTRIBUTES"))
                .range(range)
                .withFix(AddMissingSupertraitImplFix(impl))
                .create()
        }
    }

    // E0118: Can impl only `struct`s, `enum`s and `union`s (when not implementing a trait)
    private fun checkImplForNonAdtError(holder: RsAnnotationHolder, impl: RsImplItem) {
        if (impl.`for` != null) return
        val typeRef = impl.typeReference ?: return
        if (typeRef.skipParens() is RsTraitType) return
        val type = typeRef.type
        if (impl.queryAttributes.langAttribute != null) {
            // There are some special rules for #[lang] items, see:
            // https://doc.rust-lang.org/unstable-book/language-features/lang-items.html)
            return
        }
        if (type !is TyAdt && type !is TyTraitObject && type != TyUnknown) {
            RsDiagnostic.ImplForNonAdtError(typeRef).addToHolder(holder)
        }
    }

    private fun checkConstTraitImpl(holder: RsAnnotationHolder, impl: RsImplItem) {
        val const = impl.const ?: return
        CONST_TRAIT_IMPL.check(holder, const, "const trait impls")
    }

    // E0322: Explicit impls for the `Sized` trait are not permitted
    // E0328: Explicit impls for the `Unsized` trait are not permitted
    private fun checkForbiddenImpl(holder: RsAnnotationHolder, traitRef: RsTraitRef, trait: RsTraitItem) {
        if (trait == trait.knownItems.Sized) RsDiagnostic.ImplSizedError(traitRef).addToHolder(holder)
        if (trait == trait.knownItems.Unsize) RsDiagnostic.ImplUnsizeError(traitRef).addToHolder(holder)
    }

    // E0120: Drop can be only implemented by structs and enums
    private fun checkImplDropForNonAdtError(
        holder: RsAnnotationHolder,
        impl: RsImplItem,
        traitRef: RsTraitRef,
        trait: RsTraitItem
    ) {
        if (trait != trait.knownItems.Drop) return

        if (impl.typeReference?.type is TyAdt?) return

        RsDiagnostic.ImplDropForNonAdtError(traitRef).addToHolder(holder)
    }

    private fun checkImplBothCopyAndDrop(holder: RsAnnotationHolder, impl: RsImplItem, trait: RsTraitItem) {
        checkImplBothCopyAndDrop(holder, impl.typeReference?.type ?: return, impl.traitRef ?: return, trait)
    }

    private fun checkImplBothCopyAndDrop(holder: RsAnnotationHolder, attr: RsAttr) {
        // TODO: support `#[derive(std::marker::Copy)]`
        val deriveCopy = attr.metaItem.metaItemArgs?.metaItemList?.find { it.name == "Copy" } ?: return
        val selfType = (attr.parent as? RsStructOrEnumItemElement)?.declaredType ?: return
        checkImplBothCopyAndDrop(holder, selfType, deriveCopy, attr.knownItems.Copy ?: return)
    }

    // E0184: Cannot implement both Copy and Drop
    private fun checkImplBothCopyAndDrop(
        holder: RsAnnotationHolder,
        self: Ty,
        element: PsiElement,
        trait: RsTraitItem
    ) {
        val oppositeTrait = when (trait) {
            trait.knownItems.Drop -> trait.knownItems.Copy
            trait.knownItems.Copy -> trait.knownItems.Drop
            else -> null
        } ?: return
        if (!trait.implLookup.canSelect(TraitRef(self, oppositeTrait.withSubst()))) return

        RsDiagnostic.ImplBothCopyAndDropError(element).addToHolder(holder)
    }

    // E0116: Cannot define inherent `impl` for a type outside of the crate where the type is defined
    private fun checkInherentImplSameCrate(holder: RsAnnotationHolder, impl: RsImplItem) {
        if (impl.traitRef != null) return  // checked in [checkTraitImplOrphanRules]
        val typeReference = impl.typeReference ?: return
        val element = when (val type = typeReference.type) {
            is TyAdt -> type.item
            is TyTraitObject -> type.traits.first().element
            else -> return
        }
        if (impl.containingCrate != element.containingCrate) {
            RsDiagnostic.InherentImplDifferentCrateError(typeReference).addToHolder(holder)
        }
    }

    // E0117: Only traits defined in the current crate can be implemented for arbitrary types
    private fun checkTraitImplOrphanRules(holder: RsAnnotationHolder, impl: RsImplItem) {
        val implContainingCrate = impl.containingCrate
        if (!checkOrphanRules(impl) { it.containingCrate == implContainingCrate }) {
            val traitRef = impl.traitRef ?: return
            RsDiagnostic.TraitImplOrphanRulesError(traitRef).addToHolder(holder)
        }
    }

    private fun checkTypeAlias(holder: RsAnnotationHolder, ta: RsTypeAlias) {
        when (val owner = ta.owner) {
            is RsAbstractableOwner.Trait -> {
                ta.typeReference?.let { ASSOCIATED_TYPE_DEFAULTS.check(holder, it, "associated type defaults") }
                ta.typeParameterList?.let { GENERIC_ASSOCIATED_TYPES.check(holder, it, "generic associated types") }
                ta.whereClause?.let { GENERIC_ASSOCIATED_TYPES.check(holder, it, "where clauses on associated types") }
            }
            is RsAbstractableOwner.Impl -> {
                if (owner.isInherent) {
                    INHERENT_ASSOCIATED_TYPES.check(holder, ta, "inherent associated types")
                }
                ta.typeParameterList?.let { GENERIC_ASSOCIATED_TYPES.check(holder, it, "generic associated types") }
                ta.whereClause?.let { GENERIC_ASSOCIATED_TYPES.check(holder, it, "where clauses on associated types") }
            }
            is RsAbstractableOwner.Foreign -> {
                EXTERN_TYPES.check(holder, ta, "extern types")
            }
            else -> {}
        }

        checkDuplicates(holder, ta)
    }

    private fun checkUnary(holder: RsAnnotationHolder, o: RsUnaryExpr) {
        val box = o.box
        if (box != null) {
            BOX_SYNTAX.check(holder, box, "`box` expression syntax")
        }

        val raw = o.raw
        if (raw != null) {
            RAW_REF_OP.check(holder, raw, "`raw address of` syntax")
        }
    }

    private fun checkBinary(holder: RsAnnotationHolder, o: RsBinaryExpr) {
        if (o.isComparisonBinaryExpr() && (o.left.isComparisonBinaryExpr() || o.right.isComparisonBinaryExpr())) {
            holder.createErrorAnnotation(o, "Chained comparison operator require parentheses", AddTurbofishFix())
        }
    }

    private fun checkTypeArgumentList(holder: RsAnnotationHolder, args: RsTypeArgumentList) {
        checkRedundantColonColon(holder, args)
        checkConstArguments(holder, args.exprList)
    }

    private fun checkValueParameterList(holder: RsAnnotationHolder, args: RsValueParameterList) {
        checkRedundantColonColon(holder, args)
    }

    private fun checkRedundantColonColon(holder: RsAnnotationHolder, args: RsElement) {
        // For some reason `::(i32) -> i32` in `Fn::(i32) -> i32` has `RsValueParameterList` instead of `RsTypeArgumentList`.
        // `RsValueParameterList` and `RsTypeArgumentList` shouldn't have common interfaces
        // So we have to use low level ASTNode API to avoid code duplication
        val coloncolon = args.node.findChildByType(RsElementTypes.COLONCOLON)?.psi ?: return
        // `::` is redundant only in types
        if (!isTypePart(args)) return
        val annotation = holder.newWeakWarningAnnotation(coloncolon, "Redundant `::`", RemoveElementFix(coloncolon))
            ?: return
        annotation.highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL).create()
    }

    private fun isTypePart(args: RsElement): Boolean {
        val ancestor = args.ancestors.firstOrNull {
            PsiTreeUtil.instanceOf(it, RsTypeReference::class.java, RsTraitRef::class.java, RsExpr::class.java)
        }
        return ancestor != null && ancestor !is RsExpr
    }

    private fun checkValueArgumentList(holder: RsAnnotationHolder, args: RsValueArgumentList) {
        val (expectedCount, functionType, function) = args.getFunctionCallContext() ?: return

        val realCount = args.exprList.size
        val fixes = if (function != null) {
            ChangeFunctionSignatureFix.createIfCompatible(args, function)
        } else {
            emptyList()
        }

        if (realCount < expectedCount) {
            // Mark only the right parenthesis
            val rparen = args.rparen
            if (rparen != null) {
                holder.newErrorAnnotation(rparen, null)?.create()
            }

            // But enable the quick fix on the whole argument range
            RsDiagnostic.IncorrectFunctionArgumentCountError(
                args, expectedCount, realCount, functionType,
                listOf(FillFunctionArgumentsFix(args)) + fixes,
                textAttributes = TextAttributesKey.createTextAttributesKey("DEFAULT_TEXT_ATTRIBUTES")
            ).addToHolder(holder)
        } else if (!functionType.variadic && realCount > expectedCount) {
            args.exprList.drop(expectedCount).forEach {
                holder.newErrorAnnotation(it, null)?.create()
            }

            RsDiagnostic.IncorrectFunctionArgumentCountError(
                args, expectedCount, realCount, functionType,
                fixes = listOf(RemoveRedundantFunctionArgumentsFix(args, expectedCount)) + fixes,
                textAttributes = TextAttributesKey.createTextAttributesKey("DEFAULT_TEXT_ATTRIBUTES")
            )
                .addToHolder(holder)
        }

        if (realCount == expectedCount && fixes.isNotEmpty()) {
            val builder = holder.holder.newSilentAnnotation(HighlightSeverity.ERROR)
                .textAttributes(TextAttributesKey.createTextAttributesKey("DEFAULT_TEXT_ATTRIBUTES"))
                .range(args.textRange)
            for (fix in fixes) {
                builder.withFix(fix)
            }
            builder.create()
        }
    }

    private fun checkCondition(holder: RsAnnotationHolder, element: RsCondition) {
        val pat = element.pat
        if (pat != null && pat.isIrrefutable) {
            IRREFUTABLE_LET_PATTERNS.check(holder, pat, "irrefutable let pattern")
        }
        if (pat is RsOrPat) {
            IF_WHILE_OR_PATTERNS.check(
                holder,
                pat.patList.first(),
                pat.patList.last(),
                "multiple patterns in `if let` and `while let` are unstable"
            )
        }
    }

    private fun checkConstant(holder: RsAnnotationHolder, element: RsConstant) {
        collectDiagnostics(holder, element)
        checkDuplicates(holder, element)
    }

    private fun checkFunction(holder: RsAnnotationHolder, fn: RsFunction) {
        collectDiagnostics(holder, fn)
        checkDuplicates(holder, fn)
        checkTypesAreSized(holder, fn)
        checkEmptyFunctionReturnType(holder, fn)

        fn.innerAttrList.forEach { checkStartAttribute(holder, it) }
        fn.outerAttrList.forEach { checkStartAttribute(holder, it) }
    }

    private fun collectDiagnostics(holder: RsAnnotationHolder, element: RsInferenceContextOwner) {
        for (it in element.inference.diagnostics) {
            if (it.inspectionClass == javaClass) it.addToHolder(holder)
        }
    }

    private fun checkAttr(holder: RsAnnotationHolder, attr: RsAttr) {
        checkDeriveAttribute(holder, attr)
        checkInlineAttr(holder, attr)
        checkReprAttribute(holder, attr)

        if (attr.owner !is RsFunction)
            checkStartAttribute(holder, attr)
    }

    private fun checkDeriveAttribute(holder: RsAnnotationHolder, attr: RsAttr) {
        if (!attr.isBuiltinWithName("derive")) return

        if (attr.owner is RsStructOrEnumItemElement) {
            checkImplBothCopyAndDrop(holder, attr)
        } else {
            RsDiagnostic.DeriveAttrUnsupportedItem(attr).addToHolder(holder)
        }
    }

    // E0132: Invalid `start` attribute
    private fun checkStartAttribute(holder: RsAnnotationHolder, attr: RsAttr) {
        if (!attr.isBuiltinWithName("start")) return

        START.check(holder, attr.metaItem, "#[start] function")

        when (val owner = attr.owner) {
            is RsFunction -> {
                // Check if signature matches `fn(isize, *const *const u8) -> isize`
                val params = owner.valueParameters
                if (owner.returnType !is TyInteger.ISize) {
                    RsDiagnostic.InvalidStartAttrError.ReturnMismatch(owner.retType?.typeReference ?: owner.identifier)
                        .addToHolder(holder)
                }
                if (params.size != 2) {
                    RsDiagnostic.InvalidStartAttrError.InvalidParam(owner.identifier)
                        .addToHolder(holder)
                    // Don't check specific param types if param count is invalid to avoid overloading the user
                    // with errors
                    return
                }
                if (params[0].typeReference?.type !is TyInteger.ISize) {
                    RsDiagnostic.InvalidStartAttrError.InvalidParam(params[0].typeReference ?: params[0], 0)
                        .addToHolder(holder)
                }
                if (params[1].typeReference?.type?.isEquivalentTo(TyPointer(
                        TyPointer(TyInteger.U8.INSTANCE, Mutability.IMMUTABLE),
                        Mutability.IMMUTABLE
                    )) == false
                ) {
                    RsDiagnostic.InvalidStartAttrError.InvalidParam(params[1].typeReference ?: params[1], 1)
                        .addToHolder(holder)
                }
            }
            else ->
                RsDiagnostic
                    .InvalidStartAttrError.InvalidOwner(attr.metaItem.path?.referenceNameElement ?: attr.metaItem)
                    .addToHolder(holder)
        }
    }

    private fun checkReprAttribute(holder: RsAnnotationHolder, attr: RsAttr) {
        if (!attr.isBuiltinWithName("repr")) return

        val owner = attr.owner ?: return

        val reprArgs = attr.metaItem.metaItemArgs?.metaItemList.orEmpty()

        check@ for (reprArg in reprArgs) {
            val reprName = reprArg.name ?: continue

            val errorText = when (reprName) {
                "C", "transparent", "align" -> when (owner) {
                    is RsStructItem, is RsEnumItem -> continue@check
                    else -> "$reprName attribute should be applied to struct, enum, or union"
                }

                in TyInteger.NAMES -> when (owner) {
                    is RsEnumItem -> continue@check
                    else -> "$reprName attribute should be applied to enum"
                }

                "packed", "simd" -> when (owner) {
                    is RsStructItem -> continue@check
                    else -> "$reprName attribute should be applied to struct or union"
                }

                else -> {
                    RsDiagnostic.UnrecognizedReprAttribute(reprArg, reprName).addToHolder(holder)
                    continue@check
                }
            }

            RsDiagnostic.ReprAttrUnsupportedItem(reprArg, errorText).addToHolder(holder)
        }

        // E0084: Enum with no variants can't have `repr` attribute
        val enum = owner as? RsEnumItem ?: return
        // Not using `enum.variants` to avoid false positive for enum without body
        if (enum.enumBody?.enumVariantList?.isEmpty() == true) {
            RsDiagnostic.ReprForEmptyEnumError(attr).addToHolder(holder)
        }
    }

    // E0518: Inline attribute is allowed only on functions
    private fun checkInlineAttr(holder: RsAnnotationHolder, attr: RsAttr) {
        if (!attr.isBuiltinWithName("inline")) return

        val owner = attr.owner
        if (owner !is RsFunction && owner !is RsLambdaExpr) {

            val metaItem = attr.metaItem
            RsDiagnostic.IncorrectlyPlacedInlineAttr(metaItem.path?.referenceNameElement ?: metaItem, attr)
                .addToHolder(holder)
        }
    }

    private fun checkRetExpr(holder: RsAnnotationHolder, ret: RsRetExpr) {
        if (ret.expr != null) return
        val fn = ret.ancestors.find {
            it is RsFunction || it is RsLambdaExpr || it is RsBlockExpr && it.isAsync
        } as? RsFunction ?: return
        val retType = fn.retType?.typeReference?.type ?: return
        if (retType is TyUnit) return
        RsDiagnostic.ReturnMustHaveValueError(ret).addToHolder(holder)
    }

    private fun checkExternCrate(holder: RsAnnotationHolder, el: RsExternCrateItem) {
        if (el.self != null) {
            EXTERN_CRATE_SELF.check(holder, el, "`extern crate self`")
            if (el.alias == null) {
                // The current version of rustc (1.33.0) prints
                // "`extern crate self;` requires renaming" error message
                // but it looks like quite unclear
                holder.createErrorAnnotation(el, "`extern crate self` requires `as name`")
            }
        }
    }

    private fun checkPolybound(holder: RsAnnotationHolder, o: RsPolybound) {
        if (o.lparen != null && o.bound.lifetime != null) {
            holder.createErrorAnnotation(o, "Parenthesized lifetime bounds are not supported")
        }
    }

    private fun checkBlockExpr(holder: RsAnnotationHolder, expr: RsBlockExpr) {
        val label = expr.labelDecl
        if (label != null) {
            LABEL_BREAK_VALUE.check(holder, label, "label on block")
        }
    }

    // E0586: inclusive range with no end
    private fun checkRangeExpr(holder: RsAnnotationHolder, range: RsRangeExpr) {
        val dotdoteq = range.dotdoteq ?: range.dotdotdot ?: return
        if (dotdoteq == range.dotdotdot) {
            // rustc doesn't have an error code for this ("error: unexpected token: `...`")
            holder.createErrorAnnotation(
                dotdoteq,
                "`...` syntax is deprecated. Use `..` for an exclusive range or `..=` for an inclusive range"
            )
            return
        }
        val expr = range.exprList.singleOrNull() ?: return
        if (expr.startOffsetInParent < dotdoteq.startOffsetInParent) {
            RsDiagnostic.InclusiveRangeWithNoEndError(dotdoteq).addToHolder(holder)
        }
    }

    private fun checkBreakExpr(holder: RsAnnotationHolder, expr: RsBreakExpr) {
        checkLabelReferenceOwner(holder, expr)
        checkLabelRefOwnerPlacementCorrectness(holder, expr)
    }

    private fun checkContExpr(holder: RsAnnotationHolder, expr: RsContExpr) {
        checkLabelReferenceOwner(holder, expr)
        checkLabelRefOwnerPlacementCorrectness(holder, expr)
    }

    private fun checkLabelReferenceOwner(holder: RsAnnotationHolder, expr: RsLabelReferenceOwner) {
        if (expr.label == null) {
            val block = expr.ancestors.filterIsInstance<RsLabeledExpression>().firstOrNull() as? RsBlockExpr ?: return
            if (block.labelDecl != null) {
                val element = when (expr) {
                    is RsBreakExpr -> expr.`break`
                    is RsContExpr -> expr.`continue`
                    else -> return
                }
                RsDiagnostic.UnlabeledControlFlowExpr(element).addToHolder(holder)
            }
        }
    }

    // Detect E0267, E0268: break/continue used outside of loop
    private fun checkLabelRefOwnerPlacementCorrectness(holder: RsAnnotationHolder, expr: RsLabelReferenceOwner) {
        for (ancestor in expr.ancestors) {
            // We are inside a loop, all is good
            if (ancestor is RsLooplikeExpr) return
            // let x = 'foo: { break 'foo: 1; }; is allowed (notice `'foo` label, without it would be invalid)
            if (ancestor is RsBlockExpr && ancestor.labelDecl != null) return
            // Reached the function definition - can't be in a loop
            if (ancestor is RsFunction) break
            if (ancestor is RsLambdaExpr) {
                RsDiagnostic.LoopOnlyKeywordUsedInClosureError(expr.operator).addToHolder(holder)
                return
            }
        }
        // If we got here, we aren't inside a loop expr so emit an error
        RsDiagnostic.LoopOnlyKeywordUsedOutsideOfLoopError(expr.operator).addToHolder(holder)
    }

    private fun checkArrayType(holder: RsAnnotationHolder, o: RsArrayType) {
        collectDiagnostics(holder, o)
        val sizeExpr = o.expr
        if (sizeExpr != null && o.arraySize == null) {
            checkArraySizeExpr(holder, sizeExpr)
        }
    }

    private fun checkArrayExpr(holder: RsAnnotationHolder, o: RsArrayExpr) {
        val sizeExpr = o.sizeExpr
        if (sizeExpr != null) {
            checkArraySizeExpr(holder, sizeExpr)
        }
    }

    private fun checkArraySizeExpr(holder: RsAnnotationHolder, sizeExpr: RsExpr) {
        sizeExpr.descendantsOfTypeOrSelf<RsPathExpr>().forEach { pathExpr ->
            val ref = pathExpr.path.reference?.resolve() ?: return@forEach
            if (ref is RsConstant || ref is RsConstParameter || ref is RsFunction && ref.isConst) return@forEach
            val diagnostic = if (ref is RsPatBinding) {
                RsDiagnostic.NonConstantValueInConstantError(pathExpr)
            } else {
                RsDiagnostic.NonConstantCallInConstantError(pathExpr)
            }
            diagnostic.addToHolder(holder)
        }
    }

    private fun checkExternAbi(holder: RsAnnotationHolder, abi: RsExternAbi) {
        val litExpr = abi.litExpr ?: return
        val abiName = litExpr.stringValue ?: return
        if (abiName !in SUPPORTED_CALLING_CONVENTIONS) {
            RsDiagnostic.InvalidAbi(litExpr, abiName).addToHolder(holder)
        } else {
            val compilerFeature = SUPPORTED_CALLING_CONVENTIONS[abiName]
            compilerFeature?.check(holder, litExpr, "$abiName ABI")
        }
    }

    private fun isInTrait(o: RsVis): Boolean =
        (o.parent as? RsAbstractable)?.owner is RsAbstractableOwner.Trait

    private fun isInTraitImpl(o: RsVis): Boolean =
        (o.parent as? RsAbstractable)?.owner?.isTraitImpl == true

    private fun isInEnumVariantField(o: RsVis): Boolean {
        val field = o.parent as? RsNamedFieldDecl
            ?: o.parent as? RsTupleFieldDecl
            ?: return false
        return field.parent.parent is RsEnumVariant
    }

    private fun hasResolve(el: RsMandatoryReferenceElement): Boolean =
        !(el.reference.resolve() != null || el.reference.multiResolve().size > 1)
}

private fun RsExpr?.isComparisonBinaryExpr(): Boolean {
    val op = (this as? RsBinaryExpr)?.operatorType ?: return false
    return op is ComparisonOp || op is EqualityOp
}

private fun checkDuplicates(
    holder: RsAnnotationHolder,
    element: RsNameIdentifierOwner,
    scope: PsiElement = element.parent,
    recursively: Boolean = false
) {
    if (element.isCfgUnknown) return
    val owner = if (scope is RsMembers) scope.parent else scope
    val duplicates = holder.currentAnnotationSession.duplicatesByNamespace(scope, recursively)
    val ns = element.namespacesForDuplicatesCheck.find { element in duplicates[it].orEmpty() }
        ?: return
    val name = element.name!!

    val identifier = element.nameIdentifier ?: element
    val message = when {
        element is RsNamedFieldDecl -> RsDiagnostic.DuplicateFieldError(identifier, name)
        element is RsEnumVariant -> RsDiagnostic.DuplicateEnumVariantError(identifier, name)
        element is RsLifetimeParameter -> RsDiagnostic.DuplicateLifetimeError(identifier, name)
        element is RsPatBinding && owner is RsValueParameterList -> {
            val parent = owner.parent as? RsFunction

            // Handled by RsDuplicatedTraitMethodBindingInspection
            if (parent?.owner is RsAbstractableOwner.Trait && parent.isAbstract) return

            RsDiagnostic.DuplicateBindingError(identifier, name)
        }
        element is RsTypeParameter -> RsDiagnostic.DuplicateTypeParameterError(identifier, name)
        owner is RsImplItem -> RsDiagnostic.DuplicateDefinitionError(identifier, name)
        else -> {
            val scopeType = when (owner) {
                is RsBlock -> "block"
                is RsMod, is RsForeignModItem -> "module"
                is RsTraitItem -> "trait"
                else -> "scope"
            }
            RsDiagnostic.DuplicateItemError(identifier, ns.itemName, name, scopeType)
        }
    }
    message.addToHolder(holder)
}

private fun checkConstGenerics(holder: RsAnnotationHolder, constParameter: RsConstParameter) {
    MIN_CONST_GENERICS.check(holder, constParameter, "min const generics")
    checkConstGenericsDefaults(holder, constParameter.expr)
    checkConstArguments(holder, listOfNotNull(constParameter.expr))

    val typeReference = constParameter.typeReference
    val ty = typeReference?.type ?: return
    if (ty !is TyInteger && ty !is TyBool && ty !is TyChar) {
        ADT_CONST_PARAMS.check(holder, typeReference, "adt const params")
    }

    val lookup = ImplLookup.relativeTo(constParameter)
    if (ProcMacroApplicationService.isEnabled() && !(lookup.isPartialEq(ty) && lookup.isEq(ty))) {
        RsDiagnostic.NonStructuralMatchTypeAsConstGenericParameter(typeReference, ty.shortPresentableText)
            .addToHolder(holder)
    }
}

private fun checkConstGenericsDefaults(holder: RsAnnotationHolder, default: RsExpr?) {
    if (default == null) return
    CONST_GENERICS_DEFAULTS.check(holder, default, "const generics defaults")
    when (default.ancestorStrict<RsGenericDeclaration>()) {
        is RsStructItem,
        is RsEnumItem,
        is RsTypeAlias,
        is RsTraitItem,
        null -> {}
        else -> RsDiagnostic.DefaultsConstGenericNotAllowed(default).addToHolder(holder)
    }
}

private fun checkConstArguments(holder: RsAnnotationHolder, args: List<RsExpr>) {
    for (expr in args) {
        val ok = when (expr) {
            is RsLitExpr, is RsBlockExpr -> true
            is RsPathExpr -> !expr.path.hasColonColon
            is RsUnaryExpr -> expr.minus != null && expr.expr?.type is TyNumeric
            else -> false
        }

        if (!ok) {
            RsDiagnostic.InvalidConstGenericArgument(expr).addToHolder(holder)
        }
    }
}

private fun checkParamAttrs(holder: RsAnnotationHolder, o: RsOuterAttributeOwner) {
    val outerAttrs = o.outerAttrList
    if (outerAttrs.isEmpty()) return
    val startElement = outerAttrs.first()
    val endElement = outerAttrs.last()
    val message = "attributes on function parameters is experimental"
    val diagnostic = when (PARAM_ATTRS.availability(startElement)) {
        NOT_AVAILABLE -> RsDiagnostic.ExperimentalFeature(startElement, endElement, message, emptyList())
        CAN_BE_ADDED -> {
            val fix = PARAM_ATTRS.addFeatureFix(startElement)
            RsDiagnostic.ExperimentalFeature(startElement, endElement, message, listOf(fix))
        }
        else -> return
    }
    diagnostic.addToHolder(holder)
}

private fun PsiElement.nameOrImportedName(): String? =
    when (this) {
        is RsNamedElement -> name
        is RsUseSpeck -> nameInScope
        else -> null
    }

private val RsNamedElement.namespacesForDuplicatesCheck: Set<Namespace>
    get() = when (this) {
        is RsConstParameter -> TYPES_N_VALUES
        else -> namespaces
    }

private fun AnnotationSession.duplicatesByNamespace(
    owner: PsiElement,
    recursively: Boolean
): Map<Namespace, Set<PsiElement>> {
    if (owner.parent is RsFnPointerType) return emptyMap()

    fun PsiElement.namespaced(): Sequence<Pair<Namespace, PsiElement>> =
        when (this) {
            is RsNamedElement -> namespacesForDuplicatesCheck
            is RsUseSpeck -> namespaces
            else -> emptySet()
        }.asSequence().map { Pair(it, this) }

    val fileMap = fileDuplicatesMap()
    fileMap[owner]?.let { return it }

    val importedNames = (owner as? RsItemsOwner)
        ?.expandedItemsCached
        ?.namedImports
        ?.asSequence()
        ?.mapNotNull { it.path.parent as? RsUseSpeck }
        .orEmpty()
    val namedChildren = owner
        .namedChildren(recursively, stopAt = RsFnPointerType::class.java)
        .filter { it !is RsMacro }
    val duplicates: Map<Namespace, Set<PsiElement>> =
        (namedChildren + importedNames)
            .filter { it !is RsExternCrateItem } // extern crates can have aliases.
            .filter {
                val name = it.nameOrImportedName()
                name != null && name != "_"
            }
            .filter { it.existsAfterExpansion && !it.isCfgUnknown }
            .flatMap { it.namespaced() }
            .groupBy { it.first }       // Group by namespace
            .map { entry ->
                val (namespace, items) = entry
                namespace to items.asSequence()
                    .map { it.second }
                    .groupBy { it.nameOrImportedName() }
                    .map { it.value }
                    .filter { it.size > 1 }
                    .flatten()
                    .toSet()
            }
            .toMap()

    fileMap[owner] = duplicates
    return duplicates
}

private fun PsiElement.namedChildren(recursively: Boolean, stopAt: Class<*>? = null): Sequence<RsNamedElement> {
    val result = mutableListOf<RsNamedElement>()
    fun go(element: PsiElement) {
        if (stopAt?.isInstance(element) == true) return
        for (child in element.children) {
            if (child is RsNamedElement) result.add(child)
            if (recursively) go(child)
        }
    }
    go(this)
    return result.asSequence()
}

private val DUPLICATES_BY_SCOPE = Key<MutableMap<
    PsiElement,
    Map<Namespace, Set<PsiElement>>>>("org.rust.ide.annotator.RsErrorAnnotator.duplicates")

private fun AnnotationSession.fileDuplicatesMap(): MutableMap<PsiElement, Map<Namespace, Set<PsiElement>>> {
    var map = getUserData(DUPLICATES_BY_SCOPE)
    if (map == null) {
        map = mutableMapOf()
        putUserData(DUPLICATES_BY_SCOPE, map)
    }
    return map
}

data class FunctionCallContext(
    val expectedParameterCount: Int,
    val functionType: FunctionType,
    val function: RsFunction? = null
)

fun RsValueArgumentList.getFunctionCallContext(): FunctionCallContext? {
    return when (val parent = parent) {
        is RsCallExpr -> parent.getFunctionCallContext()
        is RsMethodCall -> parent.getFunctionCallContext()
        else -> null
    }
}

fun RsCallExpr.getFunctionCallContext(): FunctionCallContext? {
    val path = (expr as? RsPathExpr)?.path ?: return null
    return when (val el = path.reference?.resolve()) {
        is RsFieldsOwner -> FunctionCallContext(el.fields.size, FunctionType.FUNCTION)
        is RsFunction -> {
            val owner = el.owner
            if (owner.isTraitImpl) return null
            val count = el.valueParameters.size
            val s = if (el.selfParameter != null) 1 else 0
            val functionType = if (el.isVariadic) {
                FunctionType.VARIADIC_FUNCTION
            } else {
                FunctionType.FUNCTION
            }
            FunctionCallContext(count + s, functionType, el)
        }
        is RsPatBinding -> {
            val type = el.type.stripReferences()
            // TODO: replace with more generic solution
            // when https://github.com/intellij-rust/intellij-rust/issues/6391 will be implemented
            if (type is TyFunction) {
                val letDecl = el.parent?.parent as? RsLetDecl
                if (letDecl?.expr is RsLambdaExpr) {
                    FunctionCallContext(type.paramTypes.size, FunctionType.CLOSURE)
                } else null
            } else {
                null
            }
        }
        else -> null
    }
}

fun RsMethodCall.getFunctionCallContext(): FunctionCallContext? {
    val fn = reference.resolve() as? RsFunction ?: return null
    return fn.valueParameterList?.valueParameterList?.size?.let {
        FunctionCallContext(it, if (fn.isVariadic) FunctionType.VARIADIC_FUNCTION else FunctionType.FUNCTION, fn)
    }.takeIf { fn.owner.isInherentImpl }
}

private fun isValidSelfSuperPrefix(path: RsPath): Boolean {
    if (path.self == null && path.`super` == null) return true
    if (path.path == null && path.coloncolon != null) return false
    if (path.self != null && path.path != null) return false
    if (path.`super` != null) {
        val q = path.path ?: return true
        return q.self != null || q.`super` != null
    }
    return true
}

private fun checkTypesAreSized(holder: RsAnnotationHolder, fn: RsFunction) {
    val arguments = fn.valueParameterList?.valueParameterList.orEmpty()
    val retType = fn.retType
    if (arguments.isEmpty() && retType == null) return

    val owner = fn.owner

    fun isError(ty: Ty): Boolean = !ty.isSized() &&
        // '?Sized' type parameter types in abstract trait method is not an error
        !(owner is RsAbstractableOwner.Trait && fn.isAbstract)

    for (arg in arguments) {
        val typeReference = arg.typeReference ?: continue
        val ty = typeReference.type
        if (isError(ty)) {
            RsDiagnostic.SizedTraitIsNotImplemented(typeReference, ty).addToHolder(holder)
        }
    }

    val typeReference = retType?.typeReference ?: return
    val ty = typeReference.type
    if (isError(ty)) {
        RsDiagnostic.SizedTraitIsNotImplemented(typeReference, ty).addToHolder(holder)
    }
}

private fun checkEmptyFunctionReturnType(holder: RsAnnotationHolder, fn: RsFunction) {
    val block = fn.block ?: return
    val rbrace = block.rbrace ?: return
    val returnType = fn.returnType
    if (returnType is TyInfer.TyVar ||
        returnType is TyUnit ||
        returnType is TyAnon ||
        returnType.containsTyOfClass(TyUnknown::class.java)) return

    val (stmts, expr) = block.expandedStmtsAndTailExpr
    if (stmts.isEmpty() && expr == null) {
        RsDiagnostic.TypeError(rbrace, returnType, TyUnit.INSTANCE).addToHolder(holder)
    }
}

private fun RsAttr.isBuiltinWithName(target: String): Boolean {
    val name = metaItem.name ?: return false

    if (name != target) return false
    if (name !in RS_BUILTIN_ATTRIBUTES) return false

    return !hasInScope(name, MACROS)
}

private val RsPat.isTopLevel: Boolean
    get() = findBinding()?.topLevelPattern == this

private fun String?.isIllegalLifetimeName(edition: Edition?): Boolean {
    if (this == null || this in RESERVED_LIFETIME_NAMES) return false
    val name = drop(1)
    val effectiveEdition = edition ?: Edition.EDITION_2018
    return name in KEYWORDS_EDITION_2015 || effectiveEdition > Edition.EDITION_2015 && name in KEYWORDS_EDITION_2018
}

private val KEYWORDS_EDITION_2015: Set<String> = hashSetOf(
    "abstract", "become", "box", "do", "final", "macro", "override", "priv", "typeof", "unsized", "virtual", "yield",
    "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
    "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
    "trait", "true", "type", "unsafe", "use", "where", "while"
)

private val KEYWORDS_EDITION_2018: Set<String> = hashSetOf("async", "await", "dyn", "try")
