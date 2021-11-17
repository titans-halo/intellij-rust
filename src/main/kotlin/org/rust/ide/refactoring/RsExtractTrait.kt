/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring

import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.components.JBTextField
import com.intellij.ui.layout.panel
import com.intellij.usageView.BaseUsageViewDescriptor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import org.rust.ide.utils.GenericConstraints
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.openapiext.addTextChangeListener
import org.rust.openapiext.isUnitTestMode
import javax.swing.JComponent
import javax.swing.JTextField

class RsExtractTraitHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset) ?: return
        val impl = element.ancestorOrSelf<RsImplItem>() ?: return
        if (impl.traitRef != null) return
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, impl)) return

        val members = (impl.members ?: return).childrenOfType<RsItemElement>()
        if (members.isEmpty()) return
        val memberInfos = members.map { RsMemberInfo(it, false) }

        val dialog = RsExtractTraitDialog(project, impl, memberInfos)
        if (isUnitTestMode) {
            dialog.doAction()
        } else {
            dialog.show()
        }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        /* not called from the editor */
    }
}

class RsExtractTraitDialog(
    project: Project,
    private val impl: RsImplItem,
    private val memberInfos: List<RsMemberInfo>,
) : RefactoringDialog(project, false) {

    private var traitNameField: JTextField = JBTextField().apply {
        addTextChangeListener { validateButtons() }
    }

    init {
        super.init()
        title = "Extract Trait"
        validateButtons()
    }

    override fun createCenterPanel(): JComponent = panel {
        blockRow {
            cell(isFullWidth = true) {
                label("Trait name:")
            }
            traitNameField().focused()
        }
        row {
            val members = RsMemberSelectionPanel("Members to form trait", memberInfos)
            members.minimumSize = JBUI.size(0, 200)
            members.table.addMemberInfoChangeListener { validateButtons() }
            members()
        }
    }

    override fun validateButtons() {
        super.validateButtons()
        previewAction.isEnabled = false
    }

    override fun areButtonsValid(): Boolean =
        isValidRustVariableIdentifier(traitNameField.text) && memberInfos.any { it.isChecked }

    @VisibleForTesting
    public override fun doAction() {
        try {
            CommandProcessor.getInstance().executeCommand(
                project,
                { doActionUndoCommand() },
                "Extract Trait",
                null
            )
        } catch (e: Exception) {
            if (isUnitTestMode) throw e
            Logger.getInstance(RsExtractTraitHandler::class.java).error(e)
            project.showRefactoringError(e.message)
        }
    }

    private fun doActionUndoCommand() {
        val (traitName, members) = getTraitNameAndSelectedMembers()
        val processor = RsExtractTraitProcessor(impl, traitName, members)
        invokeRefactoring(processor)
    }

    private fun getTraitNameAndSelectedMembers(): Pair<String, List<RsItemElement>> {
        return if (isUnitTestMode) {
            val members = impl.members
                ?.childrenOfType<RsItemElement>()
                .orEmpty()
                .filter { it.getUserData(RS_EXTRACT_TRAIT_MEMBER_IS_SELECTED) != null }
            "Trait" to members
        } else {
            val members = memberInfos.filter { it.isChecked }.map { it.member }
            traitNameField.text to members
        }
    }
}

@TestOnly
val RS_EXTRACT_TRAIT_MEMBER_IS_SELECTED: Key<Boolean> = Key("RS_EXTRACT_TRAIT_MEMBER_IS_SELECTED")

private fun Project.showRefactoringError(message: String?, helpId: String? = null) {
    val title = RefactoringBundle.message("error.title")
    CommonRefactoringUtil.showErrorMessage(title, message, helpId, this)
}

class RsExtractTraitProcessor(
    private val impl: RsImplItem,
    private val traitName: String,
    private val members: List<RsItemElement>,
) : BaseRefactoringProcessor(impl.project) {

    private val psiFactory = RsPsiFactory(impl.project)

    override fun getCommandName(): String = "Extract Trait"

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor =
        BaseUsageViewDescriptor(impl)

    override fun findUsages(): Array<UsageInfo> = emptyArray()

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val (traitImpl, trait) = createImpls() ?: return
        moveMembersToCorrectImpls(traitImpl, trait)
        insertImpls(traitImpl, trait)
    }

    private fun createImpls(): Pair<RsImplItem, RsTraitItem>? {
        val typeText = impl.typeReference?.text ?: return null
        val typesInsideMembers = members
            .filterIsInstance<RsFunction>()
            .flatMap { function ->
                listOfNotNull(function.retType, function.valueParameterList)
                    .flatMap { it.descendantsOfType<RsTypeReference>() }
            }
        val constraints = GenericConstraints.create(impl).filterByTypeReferences(typesInsideMembers)
        val genericsStruct = impl.typeParameterList?.text.orEmpty()
        val whereClauseStruct = impl.whereClause?.text.orEmpty()
        val genericsTrait = constraints.buildTypeParameters()
        val whereClauseTrait = constraints.buildWhereClause()

        val traitImpl = psiFactory.tryCreateImplItem(
            "impl $genericsStruct $traitName $genericsTrait for $typeText $whereClauseStruct { }"
        ) ?: return null
        val trait = psiFactory.tryCreateTraitItem(
            "trait $traitName $genericsTrait $whereClauseTrait { }"
        ) ?: return null
        return traitImpl to trait
    }

    private fun moveMembersToCorrectImpls(traitImpl: RsImplItem, trait: RsTraitItem) {
        members.forEach { (it as? RsVisibilityOwner)?.vis?.delete() }
        trait.members?.addMembers(members.map { it.copy().makeAbstract(psiFactory) }, psiFactory)

        traitImpl.members?.addMembers(members, psiFactory)
        members.forEach {
            (it.prevSibling as? PsiWhiteSpace)?.delete()
            it.delete()
        }
    }

    private fun insertImpls(traitImpl: RsImplItem, trait: RsTraitItem) {
        impl.parent.addAfter(trait, impl)
        impl.parent.addAfter(traitImpl, impl)
        if (impl.members?.children?.isEmpty() == true) {
            impl.delete()
        }
    }
}

private fun PsiElement.makeAbstract(psiFactory: RsPsiFactory): PsiElement {
    when (this) {
        is RsFunction -> {
            block?.delete()
            if (semicolon == null) add(psiFactory.createSemicolon())
        }
        is RsConstant -> {
            eq?.delete()
            expr?.delete()
        }
        is RsTypeAlias -> {
            eq?.delete()
            typeReference?.delete()
        }
    }
    return this
}

private fun RsMembers.addMembers(members: List<PsiElement>, psiFactory: RsPsiFactory) {
    val rbrace = rbrace ?: return
    addBefore(psiFactory.createNewline(), rbrace)
    for (member in members) {
        addBefore(member, rbrace)
        addBefore(psiFactory.createNewline(), rbrace)
    }
}
