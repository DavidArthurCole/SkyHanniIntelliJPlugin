package skyhanni.plugin.areas.config

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingOffsetIndependentIntention
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Activates on path string literals inside any `event.<fn>(...)` call where `<fn>` is one
 * of [CONFIG_EVENT_PATH_FUNS] (`move`, `transform`, `add`, `remove`).
 * Navigates through the config class hierarchy to the referenced property.
 *
 * Prefix rules:
 *   "#profile.x.y" -> starts from ProfileSpecificStorage
 *   "#player.x.y" -> starts from PlayerSpecificStorage
 *   "x.y" -> starts from SkyHanniConfig (default)
 */
class NavigateToConfigIntention :
    SelfTargetingOffsetIndependentIntention<KtStringTemplateExpression>(
        KtStringTemplateExpression::class.java,
        { "Go to config definition" }
    ) {

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun isApplicableTo(element: KtStringTemplateExpression): Boolean {
        val literal = evaluateStringTemplate(element) ?: return false
        if (!literal.contains('.')) return false
        return element.asConfigEventPathArg() != null
    }

    @Suppress("ReturnCount")
    override fun applyTo(element: KtStringTemplateExpression, editor: Editor?) {
        val project = element.project
        val path = evaluateStringTemplate(element) ?: run {
            warn(project, "Could not evaluate string template '${element.text}'")
            return
        }
        val segments = path.split('.').toMutableList().takeIf { it.isNotEmpty() } ?: return

        val rootClassName = segments.getRootClassName()
        var current = findKtClass(element, rootClassName) ?: run {
            warn(project, "Could not find root class '$rootClassName' for path '$path'")
            return
        }

        for ((i, name) in segments.withIndex()) {
            val (prop, isInherited) = findPropertyInHierarchy(current, name, project) ?: run {
                warn(project, "Property '$name' not found in ${current.name} for path '$path'")
                return
            }

            if (isInherited) {
                (current.navigationElement as? NavigatablePsiElement)?.navigate(true)
                return
            }

            if (i == segments.lastIndex) {
                (prop.navigationElement as? NavigatablePsiElement)?.navigate(true)
                return
            }

            val typeText = prop.typeReference?.text.orEmpty()
            if (i == segments.lastIndex - 1 &&
                (typeText.startsWith("MutableMap") || typeText.startsWith("Map"))
            ) {
                (prop.navigationElement as? NavigatablePsiElement)?.navigate(true)
                return
            }

            val rawType = typeText.substringBefore('<').substringBefore('?').ifEmpty {
                warn(project, "Could not parse type of '${prop.name}' in ${current.name}")
                return
            }

            val scope = GlobalSearchScope.projectScope(project)
            val nextClass = PsiShortNamesCache.getInstance(project)
                .getClassesByName(rawType, scope)
                .firstOrNull { it.qualifiedName?.startsWith(BASE_CONFIG_PKG) == true }
                ?: run {
                    warn(project, "Config class '$rawType' not found under '$BASE_CONFIG_PKG'")
                    return
                }

            current = nextClass.navigationElement as? KtClassOrObject ?: run {
                warn(project, "Navigation target for '$rawType' is not a Kotlin class")
                return
            }
        }
    }

    private fun findKtClass(element: KtStringTemplateExpression, fqName: String): KtClassOrObject? =
        JavaPsiFacade.getInstance(element.project)
            .findClass(fqName, GlobalSearchScope.projectScope(element.project))
            ?.navigationElement as? KtClassOrObject

    private fun warn(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("⚠️ Navigate to config: $message", NotificationType.WARNING)
            .notify(project)
    }
}
