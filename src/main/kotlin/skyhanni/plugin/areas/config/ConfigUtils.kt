package skyhanni.plugin.areas.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

const val CONFIG_OPTION_ANNOTATION = "ConfigOption"
const val CATEGORY_ANNOTATION = "Category"

const val BASE_CONFIG_PKG = "at.hannibal2.skyhanni.config"
const val BASE_CONFIG_CLASS = "at.hannibal2.skyhanni.config.SkyHanniConfig"
const val PROFILE_STORAGE_CLASS = "at.hannibal2.skyhanni.config.storage.ProfileSpecificStorage"
const val PLAYER_STORAGE_CLASS = "at.hannibal2.skyhanni.config.storage.PlayerSpecificStorage"

const val PROPERTY_FQN = "io.github.notenoughupdates.moulconfig.observer.Property"

const val NOTIFICATION_GROUP = "SkyHanni Plugin"

/** FQNs that are considered config roots — traversal stops when one is reached. */
val ROOT_CONFIG_FQNS = setOf(BASE_CONFIG_CLASS, PROFILE_STORAGE_CLASS, PLAYER_STORAGE_CLASS)

fun KtClassOrObject.isAbstract() = hasModifier(KtTokens.ABSTRACT_KEYWORD)

/** True if this property carries either `@ConfigOption` or `@Category`. */
fun KtProperty.isConfigAnnotated() = annotationEntries.any {
    val name = it.shortName?.asString()
    name == CONFIG_OPTION_ANNOTATION || name == CATEGORY_ANNOTATION
}

/** Carries the result of walking one level up the config tree. */
data class ContainingPropertyResult(
    val name: String,
    val parentClass: KtClassOrObject,
    val property: KtProperty,
)

/** A single resolved segment in a config path, paired with its PSI navigation target. */
data class ConfigPathSegment(val name: String, val target: PsiElement?)

/**
 * Walks up the config class hierarchy via reverse reference search,
 * building a list of [ConfigPathSegment]s from the nearest root class down to [prop].
 *
 * Returns `null` if [prop] lives in an abstract class or its name is unavailable.
 */
fun computeConfigPathSegments(prop: KtProperty): List<ConfigPathSegment>? {
    val project = prop.project
    val segments = mutableListOf<ConfigPathSegment>()
    segments.add(ConfigPathSegment(prop.name ?: return null, prop.navigationElement))

    var currentClass = PsiTreeUtil.getParentOfType(prop, KtClassOrObject::class.java)
    if (currentClass?.isAbstract() == true) return null

    while (currentClass != null) {
        if (currentClass.fqName?.asString() in ROOT_CONFIG_FQNS) break
        val result = findContainingProperty(currentClass, project) ?: break
        segments.add(ConfigPathSegment(result.name, result.property.navigationElement))
        currentClass = result.parentClass
    }

    segments.reverse()
    return segments.takeIf { it.isNotEmpty() }
}

/**
 * Convenience over [computeConfigPathSegments] returning the dotted path string,
 * or `null` if the path cannot be computed.
 */
fun computeConfigPath(prop: KtProperty): String? =
    computeConfigPathSegments(prop)?.joinToString(".") { it.name }

/**
 * Given a config class, finds the property in another class whose type
 * references this class — i.e. walks one level up the config tree.
 *
 * Searches directly on the [KtClassOrObject] so that Kotlin-indexed type
 * references (which are not tied to the Java light class) are found correctly.
 *
 * Returns a [ContainingPropertyResult], or `null` if at the root.
 */
fun findContainingProperty(kClass: KtClassOrObject, project: Project): ContainingPropertyResult? {
    if (kClass.fqName == null) return null
    for (ref in ReferencesSearch.search(kClass, GlobalSearchScope.projectScope(project)).findAll()) {
        val prop = PsiTreeUtil.getParentOfType(ref.element, KtProperty::class.java) ?: continue
        val parentClass = PsiTreeUtil.getParentOfType(prop, KtClassOrObject::class.java) ?: continue
        val parentFqName = parentClass.fqName?.asString() ?: continue
        if (!parentFqName.startsWith(BASE_CONFIG_PKG)) continue
        if (prop.parent !is KtClassBody) continue
        return ContainingPropertyResult(prop.name ?: continue, parentClass, prop)
    }
    return null
}

/**
 * Evaluates a KtStringTemplateExpression to a plain string by resolving
 * simple local-val substitutions. Returns null if any part can't be resolved.
 */
fun evaluateStringTemplate(element: KtStringTemplateExpression): String? {
    val sb = StringBuilder()
    for (entry in element.entries) {
        when (entry) {
            is KtLiteralStringTemplateEntry -> sb.append(entry.text)
            is KtSimpleNameStringTemplateEntry -> {
                val resolved = resolveLocalValText(
                    entry.expression as? KtNameReferenceExpression ?: return null
                ) ?: return null
                sb.append(resolved)
            }
            is KtBlockStringTemplateEntry -> return null
            else -> return null
        }
    }
    return sb.toString()
}

fun resolveLocalValText(ref: KtNameReferenceExpression): String? {
    var scope: PsiElement? = ref.parent
    while (scope != null) {
        if (scope is KtBlockExpression || scope is KtFunctionLiteral) {
            val match = scope.children.filterIsInstance<KtProperty>()
                .firstOrNull { it.name == ref.getReferencedName() && !it.isVar }
            if (match != null) {
                val init = match.initializer as? KtStringTemplateExpression ?: return null
                return evaluateStringTemplate(init)
            }
        }
        scope = scope.parent
    }
    return null
}

/**
 * Finds a property by name in [kClass] or any of its supertypes within the config package.
 * Returns Pair(property, isInherited) — isInherited=true means it lives in a supertype.
 */
fun findPropertyInHierarchy(kClass: KtClassOrObject, name: String, project: Project): Pair<KtProperty, Boolean>? {
    val direct = kClass.declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == name }
    if (direct != null) return Pair(direct, false)

    val scope = GlobalSearchScope.projectScope(project)
    return kClass.superTypeListEntries.firstNotNullOfOrNull entries@{ superEntry ->
        val wholeRawType = superEntry.typeReference?.text ?: return@entries null
        val rawType = wholeRawType.substringBefore('<').substringBefore('?')
        val superPsi = PsiShortNamesCache.getInstance(project).getClassesByName(rawType, scope).firstOrNull {
            it.qualifiedName?.startsWith(BASE_CONFIG_PKG) == true
        } ?: return@entries null
        val superKt = superPsi.navigationElement as? KtClassOrObject ?: return@entries null
        val found = findPropertyInHierarchy(superKt, name, project) ?: return@entries null
        Pair(found.first, true)
    }
}

fun MutableList<String>.getRootClassName(): String = when (first()) {
    "#profile" -> PROFILE_STORAGE_CLASS.also { removeFirst() }
    "#player" -> PLAYER_STORAGE_CLASS.also { removeFirst() }
    else -> BASE_CONFIG_CLASS
}
