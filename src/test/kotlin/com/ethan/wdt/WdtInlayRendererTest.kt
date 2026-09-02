package com.ethan.wdt

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 验证行尾渲染器能够创建有效的编辑器元素 */
class WdtInlayRendererTest : BasePlatformTestCase() {
    /** 渲染器必须为行尾元素提供正数宽度 */
    fun testCreatesAfterLineEndInlayWithPositiveWidth() {
        myFixture.configureByText("Sample.kt", "val answer = 42")
        val editor = myFixture.editor
        val renderer = WdtInlayRenderer("Ethan · 2026-09-02 14:30 · init")

        val inlay = editor.inlayModel.addAfterLineEndElement(
            editor.document.getLineEndOffset(0),
            true,
            renderer,
        )

        assertNotNull(inlay)
        assertTrue(inlay!!.widthInPixels > 0)
        assertSame(renderer, inlay.renderer)
    }
}
