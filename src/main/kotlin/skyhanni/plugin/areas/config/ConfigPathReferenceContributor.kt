package skyhanni.plugin.areas.config

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.ProcessingContext
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class ConfigPathReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(KtStringTemplateExpression::class.java),
            ConfigPathReferenceProvider()
        )
    }
}

private class ConfigPathReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val str = element as? KtStringTemplateExpression ?: return PsiReference.EMPTY_ARRAY
        val literal = evaluateStringTemplate(str) ?: return PsiReference.EMPTY_ARRAY
        if (!literal.contains('.')) return PsiReference.EMPTY_ARRAY
        str.asConfigEventPathArg() ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(ConfigPathReference(str))
    }
}

private class ConfigPathReference(
    element: KtStringTemplateExpression,
) : PsiReferenceBase<KtStringTemplateExpression>(element, TextRange(1, element.textLength - 1), true) {

    @Suppress("ReturnCount")
    override fun resolve(): PsiElement? {
        val path = evaluateStringTemplate(element) ?: return null
        val segments = path.split('.').toMutableList().takeIf { it.isNotEmpty() } ?: return null
        val project = element.project

        val rootClassName = segments.getRootClassName()
        var current = findKtClass(rootClassName) ?: return null

        for ((i, name) in segments.withIndex()) {
            val (prop, isInherited) = findPropertyInHierarchy(current, name, project) ?: return null

            if (isInherited) return current.navigationElement
            else if (i == segments.lastIndex) return prop.navigationElement

            val typeText = prop.typeReference?.text ?: return null
            if (i == segments.lastIndex - 1 &&
                (typeText.startsWith("MutableMap") || typeText.startsWith("Map"))
            ) return prop.navigationElement

            val rawType = typeText.substringBefore('<').substringBefore('?').ifEmpty { return null }
            val scope = GlobalSearchScope.projectScope(project)
            val nextClass = PsiShortNamesCache.getInstance(project)
                .getClassesByName(rawType, scope)
                .firstOrNull { it.qualifiedName?.startsWith(BASE_CONFIG_PKG) == true }
                ?: return null

            current = nextClass.navigationElement as? KtClassOrObject ?: return null
        }
        return null
    }

    private fun findKtClass(fqName: String): KtClassOrObject? =
        JavaPsiFacade.getInstance(element.project)
            .findClass(fqName, GlobalSearchScope.projectScope(element.project))
            ?.navigationElement as? KtClassOrObject

    // Suppress rename refactoring - this is a config path, not a symbol reference
    override fun handleElementRename(newElementName: String) = element
}
