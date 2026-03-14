package skyhanni.plugin.areas.config

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtVisitorVoid
import java.awt.datatransfer.StringSelection

class CopyConfigPathInspection : AbstractKotlinInspection() {

    override fun getDisplayName() = "Copy config path for a @ConfigOption or @Category property"
    override fun getShortName() = "CopyConfigPath"
    override fun getGroupDisplayName() = "SkyHanni"
    override fun isEnabledByDefault() = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitProperty(property: KtProperty) {
                if (!property.isConfigAnnotated()) return
                val containingClass = PsiTreeUtil.getParentOfType(property, KtClassOrObject::class.java) ?: return
                if (containingClass.isAbstract()) return
                holder.registerProblem(
                    property.nameIdentifier ?: property,
                    "Copy config path",
                    ProblemHighlightType.INFORMATION,
                    CopyConfigPathFix()
                )
            }
        }
}

private class CopyConfigPathFix : LocalQuickFix {

    override fun getName() = "Copy config path"
    override fun getFamilyName() = name

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val prop = PsiTreeUtil.getParentOfType(descriptor.psiElement, KtProperty::class.java) ?: return
        val path = computeConfigPath(prop) ?: return

        CopyPasteManager.getInstance().setContents(StringSelection(path))

        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Copied: $path", NotificationType.INFORMATION)
            .notify(project)
    }
}
