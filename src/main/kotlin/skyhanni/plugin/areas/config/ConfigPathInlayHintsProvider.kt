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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import org.jetbrains.kotlin.psi.KtClassOrObject
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

private val HINT_COLOR = JBColor(Gray._120, Gray._140)
private val HINT_HOVER_COLOR = JBColor(Color(88, 157, 246), Color(88, 157, 246))

/**
 * Renders a clickable end-of-line config path hint for every non-abstract `@ConfigOption`
 * or `@Category` property. Each dot-separated segment is individually clickable and
 * navigates to its definition. Hovering a segment turns it link-blue.
 */
@Suppress("UnstableApiUsage")
class ConfigPathInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key = SettingsKey<NoSettings>("skyhanni.config.path")
    override val name = "Config path"
    override val previewText = null

    override fun createSettings() = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener): JComponent = JPanel()
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector = object : FactoryInlayHintsCollector(editor) {
        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val property = element as? KtProperty ?: return true
            if (!property.isConfigAnnotated()) return true
            val containingClass = PsiTreeUtil.getParentOfType(property, KtClassOrObject::class.java) ?: return true
            if (containingClass.isAbstract()) return true

            val segments = computeConfigPathSegments(property) ?: return true
            val presentation = buildPresentation(segments, editor)

            val varLine = editor.document.getLineNumber(property.valOrVarKeyword.textRange.startOffset)
            sink.addInlineElement(editor.document.getLineEndOffset(varLine), true, presentation, false)
            return true
        }

        private fun buildPresentation(segments: List<ConfigPathSegment>, editor: Editor): InlayPresentation {
            val parts = segments.flatMapIndexed { i, segment ->
                val navigable = segment.target as? NavigatablePsiElement
                val part = SegmentPresentation(segment.name, navigable, editor)
                if (i < segments.lastIndex) listOf(part, SegmentPresentation(".", null, editor))
                else listOf(part)
            }
            return factory.seq(*parts.toTypedArray())
        }
    }
}

@Suppress("UnstableApiUsage")
private class SegmentPresentation(
    private val label: String,
    private val target: NavigatablePsiElement?,
    private val editor: Editor,
) : BasePresentation() {

    private var hovered = false
    private val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    private val fontMetrics by lazy { editor.component.getFontMetrics(font) }

    override val width: Int get() = fontMetrics.stringWidth(label)
    override val height: Int get() = editor.lineHeight

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        val fm = g.getFontMetrics(font)
        g.font = font
        g.color = if (hovered && target != null) HINT_HOVER_COLOR else HINT_COLOR
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
    }

    override fun mouseExited() {
        if (!hovered) return
        hovered = false
        fireContentChanged(Rectangle(width, height))
        editor.contentComponent.cursor = Cursor.getDefaultCursor()
    }

    override fun toString() = label
}
