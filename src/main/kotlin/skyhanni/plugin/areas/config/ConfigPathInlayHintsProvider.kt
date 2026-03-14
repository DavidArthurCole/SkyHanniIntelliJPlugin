package skyhanni.plugin.areas.config

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.lang.documentation.ide.impl.DocumentationManagementHelper
import com.intellij.lang.documentation.psi.psiDocumentationTargets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

// Intermediate/link segments
private val HINT_COLOR = JBColor(Gray._130, Gray._150)

// Last segment - non-clickable, slightly darker to distinguish from links
private val HINT_LAST_COLOR = JBColor(Gray._100, Gray._120)

// Hover state for clickable segments
private val HINT_HOVER_COLOR = JBColor(Color(88, 157, 246), Color(88, 157, 246))

/**
 * Renders a boxed, clickable config path hint for every non-abstract `@ConfigOption`
 * or `@Category` property (end-of-line), and above every config class declaration (block).
 *
 * - Each dot-separated segment is a clickable link navigating to its definition.
 * - The last segment (the property itself) is non-clickable and slightly darker.
 * - Hovering a link segment for 400 ms opens the standard documentation popup.
 * - The whole hint is rendered in a rounded background box matching native inlay style.
 *
 * Doc popup path (both are `@Internal` by package but have no stable alternative):
 * [psiDocumentationTargets] -> [DocumentationManagementHelper.showQuickDoc].
 *
 * **plugin.xml**: register under `com.intellij.codeInsight.hints.inlayProvider`.
 */
@Suppress("UnstableApiUsage")
class ConfigPathInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key = SettingsKey<NoSettings>("skyhanni.config.path")
    override val name = "Config path"
    override val previewText = null

    override fun createSettings() = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector = object : FactoryInlayHintsCollector(editor) {
        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            when (element) {
                is KtProperty -> collectProperty(element, editor, sink)
                is KtClass, is KtObjectDeclaration -> collectClass(element, editor, sink)
            }
            return true
        }

        private fun collectProperty(property: KtProperty, editor: Editor, sink: InlayHintsSink) {
            if (!property.isConfigAnnotated()) return
            val containingClass = PsiTreeUtil.getParentOfType(property, KtClassOrObject::class.java) ?: return
            if (containingClass.isAbstract()) return
            val segments = computeConfigPathSegments(property) ?: return
            val presentation = buildPresentation(segments, editor, lastIsLink = false)
            val line = editor.document.getLineNumber(property.valOrVarKeyword.textRange.startOffset)
            sink.addInlineElement(editor.document.getLineEndOffset(line), true, presentation, false)
        }

        private fun collectClass(klass: KtClassOrObject, editor: Editor, sink: InlayHintsSink) {
            val segments = computeClassConfigPathSegments(klass) ?: return
            val presentation = buildPresentation(segments, editor, lastIsLink = true)
            val line = editor.document.getLineNumber(klass.textRange.startOffset)
            sink.addBlockElement(editor.document.getLineStartOffset(line),
                relatesToPrecedingText = false,
                showAbove = true,
                priority = 0,
                presentation = presentation
            )
        }

        private fun buildPresentation(
            segments: List<ConfigPathSegment>,
            editor: Editor,
            lastIsLink: Boolean,
        ): InlayPresentation {
            val dot = SegmentPresentation(".", null, editor, HINT_COLOR)
            val parts = segments.flatMapIndexed { i, segment ->
                val isLast = i == segments.lastIndex
                val navigable = if (!isLast || lastIsLink) segment.target as? NavigatablePsiElement else null
                val color = if (isLast && !lastIsLink) HINT_LAST_COLOR else HINT_COLOR
                val part = SegmentPresentation(segment.name, navigable, editor, color)
                if (!isLast) listOf(part, dot) else listOf(part)
            }
            return factory.roundWithBackground(factory.seq(*parts.toTypedArray()))
        }
    }
}

@Suppress("UnstableApiUsage")
private class SegmentPresentation(
    private val label: String,
    private val target: NavigatablePsiElement?,
    private val editor: Editor,
    private val baseColor: Color,
) : BasePresentation() {

    private var hovered = false
    private val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    private val fontMetrics by lazy { editor.component.getFontMetrics(font) }
    private var docTimer: Timer? = null

    override val width: Int get() = fontMetrics.stringWidth(label)
    override val height: Int get() = editor.lineHeight

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        val fm = g.getFontMetrics(font)
        g.font = font
        g.color = if (hovered && target != null) HINT_HOVER_COLOR else baseColor
        g.drawString(label, 0, (height - fm.height) / 2 + fm.ascent)
    }

    override fun mouseClicked(event: MouseEvent, translated: Point) {
        if (SwingUtilities.isLeftMouseButton(event)) target?.navigate(true)
    }

    override fun mouseMoved(event: MouseEvent, translated: Point) {
        if (target == null || hovered) return
        hovered = true
        fireContentChanged(Rectangle(width, height))
        editor.contentComponent.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        docTimer = Timer(400) { showDocPopup() }.also { it.isRepeats = false; it.start() }
    }

    override fun mouseExited() {
        docTimer?.stop()
        docTimer = null
        if (!hovered) return
        hovered = false
        fireContentChanged(Rectangle(width, height))
        editor.contentComponent.cursor = Cursor.getDefaultCursor()
    }

    private fun showDocPopup() {
        val project = editor.project ?: return
        ApplicationManager.getApplication().invokeLater {
            if (!hovered) return@invokeLater
            // psiDocumentationTargets and DocumentationManagementHelper are both @Internal by
            // package annotation - there is no stable public API to trigger the doc popup for
            // an arbitrary PsiElement from outside the editor caret position.
            val docTarget = psiDocumentationTargets(target!!, null).firstOrNull() ?: return@invokeLater
            DocumentationManagementHelper.getInstance(project).showQuickDoc(editor, docTarget)
        }
    }

    override fun toString() = label
}
