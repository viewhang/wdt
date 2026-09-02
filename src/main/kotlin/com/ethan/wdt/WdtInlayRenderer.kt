package com.ethan.wdt

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/** 提示与代码之间保留固定像素间距 */
private const val INLAY_HORIZONTAL_GAP = 8

/** 使用当前编辑器配色绘制灰色斜体行尾提示 */
internal class WdtInlayRenderer(private val text: String) : EditorCustomElementRenderer {
    /** 按当前编辑器字体计算完整文本宽度 */
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(inlayFont(editor))
        return INLAY_HORIZONTAL_GAP + metrics.stringWidth(text)
    }

    /** 在行高中垂直居中绘制提示文本 */
    override fun paint(
        inlay: Inlay<*>,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes,
    ) {
        val editor = inlay.editor
        g.font = inlayFont(editor)
        g.color = editor.colorsScheme
            .getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)
            .foregroundColor ?: JBColor.GRAY
        val metrics = g.fontMetrics
        val x = targetRegion.x + INLAY_HORIZONTAL_GAP
        val y = targetRegion.y + (targetRegion.height - metrics.height) / 2 + metrics.ascent
        g.drawString(text, x.toFloat(), y.toFloat())
    }

    /** 继承编辑器字体名称和字号，仅切换为斜体 */
    private fun inlayFont(editor: Editor): Font {
        val scheme = editor.colorsScheme
        return Font(scheme.editorFontName, Font.ITALIC, scheme.editorFontSize)
    }
}
