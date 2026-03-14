package skyhanni.plugin.areas.config

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingOffsetIndependentIntention
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.getOrCreateBody

private const val MIGRATOR_CLASS = "ConfigUpdaterMigrator"
private const val MIGRATOR_VERSION_CONST = "CONFIG_VERSION"
private const val MIGRATOR_FQN_PREFIX = "at.hannibal2.skyhanni"

/**
 * Activates on `@ConfigOption` properties in non-abstract classes. Inserts an
 * `event.move(...)` call into the containing class's `onConfigFix` handler,
 * creating the companion object and function if they don't already exist.
 *
 * Reads and - if this is the first migration in the current batch - increments
 * `ConfigUpdaterMigrator.CONFIG_VERSION`. A batch is identified by scanning for
 * any existing `event.move(since = N)` call that already uses the current version;
 * if one is found, that version is reused without incrementing.
 */
class CreateConfigMigrationIntention :
    SelfTargetingOffsetIndependentIntention<KtProperty>(
        KtProperty::class.java,
        { "Create config migration" }
    ) {

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    /**
     * Deliberately avoids calling [computeConfigPath] here;
     * that triggers [com.intellij.psi.search.searches.ReferencesSearch]
     * which causes IntelliJ to re-evaluate intention applicability on discovered elements,
     * leading to infinite recursion. The path is resolved lazily in [applyTo] instead.
     */
    override fun isApplicableTo(element: KtProperty): Boolean {
        if (element.annotationEntries.none { it.shortName?.asString() == CONFIG_OPTION_ANNOTATION }) return false
        val containingClass = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java) ?: return false
        return !containingClass.isAbstract()
    }

    @Suppress("ReturnCount")
    override fun applyTo(element: KtProperty, editor: Editor?) {
        val project = element.project
        val factory = KtPsiFactory(project)

        val oldPath = computeConfigPath(element) ?: return
        val containingClass = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java) ?: return
        val companion = when (containingClass) {
            is KtClass -> findOrCreateCompanion(containingClass, factory)
            is KtObjectDeclaration -> containingClass
            else -> return
        }

        val migrationVersion = resolveMigrationVersion(project, factory)
        val onConfigFix = findOrCreateOnConfigFix(companion, factory)

        val moveCall = factory.createExpression(
            """event.move($migrationVersion, "$oldPath", "$oldPath")"""
        )

        val body = onConfigFix.bodyBlockExpression ?: return
        val rBrace = body.rBrace ?: return
        body.addBefore(factory.createNewLine(), rBrace)
        val inserted = body.addBefore(moveCall, rBrace)
        body.addBefore(factory.createNewLine(), rBrace)

        // Place the caret at the end of the toPath argument so the user can type the new path
        if (editor != null) {
            val call = inserted as? KtCallExpression
                ?: PsiTreeUtil.findChildOfType(inserted, KtCallExpression::class.java)
            val toArg: KtValueArgument = call?.valueArguments?.getOrNull(2) ?: return
            editor.caretModel.moveToOffset(toArg.textRange.endOffset - 1)
        }
    }

    /**
     * Returns the version number to use for the new `event.move` call.
     * If the current `CONFIG_VERSION` is already referenced by an existing
     * `event.move(since = N)` anywhere in the project, that version is reused.
     * Otherwise, `CONFIG_VERSION` is incremented by one and the new value is returned.
     */
    private fun resolveMigrationVersion(project: Project, factory: KtPsiFactory): Int {
        val migratorObj = findMigratorObject(project) ?: return -1
        val versionProp = migratorObj.declarations
            .filterIsInstance<KtProperty>()
            .firstOrNull { it.name == MIGRATOR_VERSION_CONST } ?: return -1
        val currentVersion = versionProp.initializer?.text?.toIntOrNull() ?: return -1

        if (migrationExistsForVersion(project, currentVersion)) return currentVersion

        val newVersion = currentVersion + 1
        versionProp.initializer!!.replace(factory.createExpression(newVersion.toString()))
        return newVersion
    }

    // Todo make it work with transform, etc.
    /** Returns true if any `event.move(since = [version], ...)` call exists in the project. */
    private fun migrationExistsForVersion(project: Project, version: Int): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        for (vFile in FilenameIndex.getAllFilesByExt(project, "kt", scope)) {
            val psi = PsiManager.getInstance(project).findFile(vFile) as? KtFile ?: continue
            for (call in PsiTreeUtil.findChildrenOfType(psi, KtCallExpression::class.java)) {
                val dot = call.parent as? KtDotQualifiedExpression ?: continue
                if (call.calleeExpression?.text != "move") continue
                if (dot.receiverExpression.text != "event") continue
                val sinceArg = call.valueArguments
                    .firstOrNull { it.getArgumentName()?.asName?.asString() == "since" }
                    ?: call.valueArguments.firstOrNull()
                if (sinceArg?.getArgumentExpression()?.text?.toIntOrNull() == version) return true
            }
        }
        return false
    }

    private fun findMigratorObject(project: Project): KtClassOrObject? {
        val scope = GlobalSearchScope.projectScope(project)
        return PsiShortNamesCache.getInstance(project)
            .getClassesByName(MIGRATOR_CLASS, scope)
            .firstOrNull { it.qualifiedName?.startsWith(MIGRATOR_FQN_PREFIX) == true }
            ?.navigationElement as? KtClassOrObject
    }

    private fun findOrCreateCompanion(containingClass: KtClass, factory: KtPsiFactory): KtObjectDeclaration {
        val existing = containingClass.companionObjects.firstOrNull()
        if (existing != null) {
            if (existing.annotationEntries.none { it.shortName?.asString() == "SkyHanniModule" }) {
                existing.addAnnotationEntry(factory.createAnnotationEntry("@SkyHanniModule"))
            }
            return existing
        }
        val body = containingClass.getOrCreateBody()
        return body.addBefore(
            factory.createObject("@SkyHanniModule\ncompanion object {}"),
            body.rBrace
        ) as KtObjectDeclaration
    }

    private fun findOrCreateOnConfigFix(companion: KtObjectDeclaration, factory: KtPsiFactory): KtFunction {
        companion.declarations.filterIsInstance<KtFunction>()
            .firstOrNull { it.name == "onConfigFix" }
            ?.let { return it }
        val body = companion.getOrCreateBody()
        return body.addBefore(
            factory.createFunction(
                "@HandleEvent\nfun onConfigFix(event: ConfigUpdaterMigrator.ConfigFixEvent) {}"
            ),
            body.rBrace
        ) as KtFunction
    }
}
