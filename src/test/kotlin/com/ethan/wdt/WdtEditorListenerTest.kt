package com.ethan.wdt

import com.intellij.openapi.editor.EditorKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 验证监听器只接受普通主编辑器 */
class WdtEditorListenerTest : BasePlatformTestCase() {
    /** 主编辑器可绑定控制器，Diff 和其他编辑器必须被排除 */
    fun testOnlySupportsMainEditor() {
        assertTrue(isSupportedEditorKind(EditorKind.MAIN_EDITOR))
        assertFalse(isSupportedEditorKind(EditorKind.DIFF))
    }

    /** 编辑器工厂必须自动创建并在释放时移除唯一控制器 */
    fun testCreatesAndReleasesController() {
        myFixture.configureByText("Sample.kt", "val answer = 42")
        val factory = com.intellij.openapi.editor.EditorFactory.getInstance()
        val editor = factory.createEditor(
            myFixture.editor.document,
            project,
            myFixture.file.virtualFile,
            false,
            EditorKind.MAIN_EDITOR,
        )

        try {
            assertNotNull(editor.getUserData(CONTROLLER_KEY))
        } finally {
            factory.releaseEditor(editor)
        }
        assertNull(editor.getUserData(CONTROLLER_KEY))
    }
}
