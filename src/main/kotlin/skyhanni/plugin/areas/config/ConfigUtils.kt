package skyhanni.plugin.areas.config

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
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

/** FQNs that are considered config roots - traversal stops when one is reached. */
val ROOT_CONFIG_FQNS = setOf(BASE_CONFIG_CLASS, PROFILE_STORAGE_CLASS, PLAYER_STORAGE_CLASS)

/**
 * Names of `ConfigFixEvent` functions that accept config path string arguments.
 * All share the same structure: arg 0 is `since: Int`, path arg(s) follow.
 * `move` is the only one with two path args (fromPath at 1, toPath at 2).
 */
val CONFIG_EVENT_PATH_FUNS = setOf("move", "transform", "add", "remove")

fun KtClassOrObject.isAbstract() = hasModifier(KtTokens.ABSTRACT_KEYWORD)

/** True if this property carries either `@ConfigOption` or `@Category`. */
fun KtProperty.isConfigAnnotated() = annotationEntries.any {
    val name = it.shortName?.asString()
    name == CONFIG_OPTION_ANNOTATION || name == CATEGORY_ANNOTATION
}

/**
 * If this string template is a path-valued argument inside an `event.<fn>(...)` call
 * where `<fn>` is one of [CONFIG_EVENT_PATH_FUNS], returns that call expression.
 * Returns `null` if the string is not in a path argument position.
 *
 * All functions share: arg 0 = `since: Int`, path arg(s) at index ≥ 1.
 * Only `move` has two path args (indices 1 and 2); all others have exactly one (index 1).
 */
fun KtStringTemplateExpression.asConfigEventPathArg(): KtCallExpression? {
    val call = PsiTreeUtil.getParentOfType(this, KtCallExpression::class.java) ?: return null
    val dot = call.parent as? KtDotQualifiedExpression ?: return null
    if (dot.receiverExpression.text != "event") return null
    val fnName = call.calleeExpression?.text ?: return null
    if (fnName !in CONFIG_EVENT_PATH_FUNS) return null
    val argIndex = call.valueArguments.indexOfFirst { it.getArgumentExpression() == this }
    if (argIndex < 1) return null
    if (fnName != "move" && argIndex > 1) return null
    return call
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
 * Computes the path segments for a config class itself - i.e. the path of the property
 * in the parent class that holds an instance of [kClass]. Used to label `class` declarations.
 *
 * Returns `null` for abstract classes, root classes, or classes with no config parent.
 */
fun computeClassConfigPathSegments(kClass: KtClassOrObject): List<ConfigPathSegment>? {
    // Abstract classes cannot be instantiated, ergo; ignore
    if (kClass.isAbstract()) return null
    // Enum classes, and their definitions are _referenced_ in other options, but cannot themselves be options.
    if (kClass is KtClass && kClass.isEnum() || kClass is KtEnumEntry) return null
    val fqName = kClass.fqName?.asString() ?: return null
    if (!fqName.startsWith(BASE_CONFIG_PKG)) return null
    if (fqName in ROOT_CONFIG_FQNS) return null
    val result = findContainingProperty(kClass, kClass.project) ?: return null
    return computeConfigPathSegments(result.property)
}

/**
 * Convenience over [computeConfigPathSegments] returning the dotted path string,
 * or `null` if the path cannot be computed.
 */
fun computeConfigPath(prop: KtProperty): String? =
    computeConfigPathSegments(prop)?.joinToString(".") { it.name }

/**
 * Given a config class, finds the property in another class whose type references this class -
 * i.e. walks one level up the config tree.
 *
 * First searches via the Kotlin PSI element directly (catches explicit type references).
 * Falls back to searching via the Java light class, which reliably catches constructor-call
 * usages where no explicit type annotation is present (e.g. `val x = SomeConfig()`).
 *
 * Returns a [ContainingPropertyResult], or `null` if at the root.
 */
fun findContainingProperty(kClass: KtClassOrObject, project: Project): ContainingPropertyResult? {
    val fqName = kClass.fqName?.asString() ?: return null
    val scope = GlobalSearchScope.projectScope(project)

    fun searchRefs(target: PsiElement): ContainingPropertyResult? {
        for (ref in ReferencesSearch.search(target, scope).findAll()) {
            val prop = PsiTreeUtil.getParentOfType(ref.element, KtProperty::class.java) ?: continue
            val parentClass = PsiTreeUtil.getParentOfType(prop, KtClassOrObject::class.java) ?: continue
            if (parentClass.fqName?.asString()?.startsWith(BASE_CONFIG_PKG) != true) continue
            if (parentClass.fqName?.asString() == fqName) continue
            if (prop.parent !is KtClassBody) continue
            return ContainingPropertyResult(prop.name ?: continue, parentClass, prop)
        }
        return null
    }

    return searchRefs(kClass)
        ?: JavaPsiFacade.getInstance(project).findClass(fqName, scope)?.let { searchRefs(it) }
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
 * Returns Pair(property, isInherited) - isInherited=true means it lives in a supertype.
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