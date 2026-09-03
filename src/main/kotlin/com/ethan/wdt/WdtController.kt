package com.ethan.wdt

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.openapi.vcs.ex.LineStatusTracker
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 连续换行停止后才发起 Git 查询 */
private const val DEFAULT_DEBOUNCE_MILLIS = 150L

/** 只记录异常的 VCS 查询，不向用户弹出通知 */
private val LOG = logger<WdtController>()

/** 使用递增序号判定异步结果是否仍属于当前光标 */
internal class WdtRequestState {
    private var current = 0L

    /** 创建并返回新的当前请求序号 */
    fun next(): Long = ++current

    /** 使所有已经发出的请求立即过期 */
    fun invalidate() {
        current++
    }

    /** 判断指定请求是否仍为最后一次请求 */
    fun isCurrent(request: Long): Boolean = request == current
}

/** 管理单个编辑器中的查询、防抖、失效事件和唯一行尾提示 */
internal class WdtController(
    private val editor: Editor,
    private val loadCommitInfo: suspend (VirtualFile, Document, Int) -> GitCommitInfo?,
    private val invalidateFile: (VirtualFile) -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : CaretListener, DocumentListener, Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val requests = WdtRequestState()
    private var queryJob: Job? = null
    private var inlay: Inlay<*>? = null
    private var requestedLine: Int? = null

    init {
        editor.caretModel.addCaretListener(this, this)
        editor.document.addDocumentListener(this, this)
        editor.project?.let(LineStatusTrackerManager::getInstanceImpl)?.let { trackerManager ->
            trackerManager.addTrackerListener(object : LineStatusTrackerManager.Listener {
                /** 当前文档的行状态跟踪器创建后重新查询，避免缓存未映射的修改行 */
                override fun onTrackerAdded(tracker: LineStatusTracker<*>) {
                    if (tracker.document == editor.document) scheduleSourceChanged()
                }
            }, this)
            trackerManager.requestTrackerFor(editor.document, this)
        }
        editor.project?.messageBus?.connect(this)?.let { connection ->
            connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                /** 文件保存或外部变化后使当前来源失效 */
                override fun after(events: List<VFileEvent>) {
                    val currentFile = currentFile() ?: return
                    if (events.any { it.file == currentFile }) scheduleSourceChanged()
                }
            })
            connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
                val currentFile = currentFile()
                if (currentFile != null && VfsUtilCore.isAncestor(repository.root, currentFile, false)) {
                    scheduleSourceChanged()
                }
            })
            connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsMappingListener {
                scheduleSourceChanged()
            })
        }
        refresh(editor.caretModel.logicalPosition.line, force = true)
    }

    /** 主光标换行时立即清除旧提示并查询新行 */
    override fun caretPositionChanged(event: CaretEvent) {
        val caret = event.caret ?: return
        if (caret == editor.caretModel.primaryCaret) {
            refresh(caret.logicalPosition.line)
        }
    }

    /** 文档变化后清除缓存并重新查询当前行 */
    override fun documentChanged(event: DocumentEvent) {
        sourceChanged()
    }

    /** 统一处理文档、文件、仓库和 VCS 映射变化 */
    private fun sourceChanged() {
        if (editor.isDisposed) return
        currentFile()?.let(invalidateFile)
        requestedLine = null
        refresh(editor.caretModel.logicalPosition.line, force = true)
    }

    /** 释放编辑器关联的后台任务和视觉元素 */
    override fun dispose() {
        requests.invalidate()
        queryJob?.cancel()
        editor.project?.let(LineStatusTrackerManager::getInstanceImpl)
            ?.releaseTrackerFor(editor.document, this)
        scope.cancel()
        clearInlay()
    }

    /** 仅在换行或明确强制刷新时创建新请求 */
    private fun refresh(line: Int, force: Boolean = false) {
        if (!force && requestedLine == line) return
        requestedLine = line
        clearInlay()
        queryJob?.cancel()
        val file = currentFile()
        if (file == null) {
            requests.invalidate()
            return
        }
        val request = requests.next()
        queryJob = scope.launch {
            delay(debounceMillis)
            val commitInfo = try {
                loadCommitInfo(file, editor.document, line)
            } catch (error: VcsException) {
                LOG.warn(error)
                null
            }
            currentCoroutineContext().ensureActive()
            applyResult(request, file, line, commitInfo)
        }
    }

    /** 将异步来源变化事件切换到控制器的 EDT 协程 */
    private fun scheduleSourceChanged() {
        scope.launch { sourceChanged() }
    }

    /** 返回当前编辑器文档对应的本地文件 */
    private fun currentFile(): VirtualFile? {
        return FileDocumentManager.getInstance().getFile(editor.document)
    }

    /** 仅在编辑器上下文完全未变化时创建行尾提示 */
    private fun applyResult(
        request: Long,
        file: VirtualFile,
        line: Int,
        commitInfo: GitCommitInfo?,
    ) {
        if (editor.isDisposed || !requests.isCurrent(request)) return
        if (currentFile() != file) return
        if (editor.caretModel.logicalPosition.line != line) return
        if (commitInfo == null || line !in 0 until editor.document.lineCount) return

        val offset = editor.document.getLineEndOffset(line)
        inlay = editor.inlayModel.addAfterLineEndElement(
            offset,
            true,
            WdtInlayRenderer(commitInfo.displayText()),
        )
    }

    /** 确保同一编辑器不会残留多个 wdt 提示 */
    private fun clearInlay() {
        inlay?.takeIf { it.isValid }?.dispose()
        inlay = null
    }
}
