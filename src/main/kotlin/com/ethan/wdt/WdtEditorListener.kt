package com.ethan.wdt

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

/** 保存编辑器唯一控制器，避免重复绑定监听器 */
internal val CONTROLLER_KEY = Key.create<WdtController>("com.ethan.wdt.controller")

/** 判断编辑器类型是否属于插件首版支持范围 */
internal fun isSupportedEditorKind(editorKind: EditorKind): Boolean {
    return editorKind == EditorKind.MAIN_EDITOR
}

/** 在本地主编辑器创建和释放时管理 wdt 控制器 */
internal class WdtEditorListener : EditorFactoryListener {
    /** 只为具有项目和本地文件的主编辑器创建控制器 */
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (!isSupportedEditorKind(editor.editorKind)) return
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!file.isInLocalFileSystem || editor.getUserData(CONTROLLER_KEY) != null) return

        val service = project.service<GitCommitInfoService>()
        val controller = WdtController(
            editor = editor,
            loadCommitInfo = service::findCommitInfo,
            invalidateFile = service::invalidate,
        )
        editor.putUserData(CONTROLLER_KEY, controller)
    }

    /** 释放编辑器时同步取消查询并移除 Inlay */
    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        editor.getUserData(CONTROLLER_KEY)?.let(Disposer::dispose)
        editor.putUserData(CONTROLLER_KEY, null)
    }
}
