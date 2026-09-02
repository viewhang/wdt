package com.ethan.wdt

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl
import com.intellij.openapi.vfs.VirtualFile
import git4idea.annotate.GitFileAnnotation
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 插件只接受 Git 的标准 VCS 名称 */
private const val GIT_VCS_NAME = "Git"

/** 这些状态不存在可供注释的已提交内容 */
private val NON_ANNOTATABLE_STATUSES = setOf(
    FileStatus.UNKNOWN,
    FileStatus.ADDED,
    FileStatus.IGNORED,
)

/** 唯一标识一次文件逐行提交快照 */
internal data class CommitSnapshotKey(
    val documentStamp: Long,
    val fileStamp: Long,
    val revision: String,
)

/** 使用弱文件键保存最后一次有效快照，避免缓存延长文件生命周期 */
internal class LineCommitCache<K : Any> {
    /** 将快照键和逐行提交信息作为一个原子缓存项 */
    private data class Entry(
        val key: CommitSnapshotKey,
        val lines: Map<Int, GitCommitInfo>,
    )

    private val cacheLock = Any()
    private val entries = WeakHashMap<K, Entry>()
    private val versions = WeakHashMap<K, Long>()

    /** 仅在文档、文件和仓库修订均未变化时返回缓存 */
    fun get(owner: K, key: CommitSnapshotKey): Map<Int, GitCommitInfo>? {
        return synchronized(cacheLock) {
            entries[owner]?.takeIf { it.key == key }?.lines
        }
    }

    /** 返回用于阻止失效前查询重新写回的当前代次 */
    fun version(owner: K): Long = synchronized(cacheLock) {
        versions[owner] ?: 0L
    }

    /** 仅在文件未被主动失效时写入查询得到的不可变快照 */
    fun replaceIfCurrent(
        owner: K,
        version: Long,
        key: CommitSnapshotKey,
        lines: Map<Int, GitCommitInfo>,
    ): Boolean = synchronized(cacheLock) {
        if ((versions[owner] ?: 0L) != version) return@synchronized false
        entries[owner] = Entry(key, lines.toMap())
        true
    }

    /** 删除文件快照并推进代次，使后台旧查询不能重新写回 */
    fun invalidate(owner: K) {
        synchronized(cacheLock) {
            entries.remove(owner)
            versions[owner] = (versions[owner] ?: 0L) + 1
        }
    }
}

/** 使用弱键锁保证同一所有者的异步操作不会并行执行 */
internal class PerKeyMutex<K : Any> {
    private val locks = Collections.synchronizedMap(WeakHashMap<K, Mutex>())

    /** 在当前键对应的互斥区段内执行操作 */
    suspend fun <T> runExclusive(key: K, action: suspend () -> T): T {
        return lockFor(key).withLock { action() }
    }

    /** 原子取得同一键复用的互斥锁 */
    private fun lockFor(key: K): Mutex = synchronized(locks) {
        locks.getOrPut(key) { Mutex() }
    }
}

/** 将当前文档行映射为只包含已提交行的不可变快照 */
internal fun buildLineCommits(
    documentLineCount: Int,
    toAnnotatedLine: (Int) -> Int,
    resolveCommit: (Int) -> GitCommitInfo?,
): Map<Int, GitCommitInfo> = buildMap {
    repeat(documentLineCount) { currentLine ->
        val annotatedLine = toAnnotatedLine(currentLine)
        if (annotatedLine >= 0) {
            resolveCommit(annotatedLine)?.let { put(currentLine, it) }
        }
    }
}

/** 通过 IntelliJ VCS 注释 API 提供当前文档行的提交信息 */
@Service(Service.Level.PROJECT)
internal class GitCommitInfoService(private val project: Project) {
    private val cache = LineCommitCache<VirtualFile>()
    private val fileMutexes = PerKeyMutex<VirtualFile>()

    /** 在 IO 调度器返回当前行的已提交信息 */
    suspend fun findCommitInfo(file: VirtualFile, document: Document, line: Int): GitCommitInfo? {
        return withContext(Dispatchers.IO) {
            fileMutexes.runExclusive(file) {
                currentCoroutineContext().ensureActive()
                findCommitInfoLocked(file, document, line)
            }
        }
    }

    /** 主动清除文件缓存并使尚未完成的旧查询结果失效 */
    fun invalidate(file: VirtualFile) {
        cache.invalidate(file)
    }

    /** 在同文件互斥区段内查询或生成逐行提交快照 */
    private suspend fun findCommitInfoLocked(
        file: VirtualFile,
        document: Document,
        line: Int,
    ): GitCommitInfo? {
        if (line !in 0 until document.lineCount) return null

        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val vcs = vcsManager.getVcsFor(file) ?: return null
        if (vcs.name != GIT_VCS_NAME) return null
        val fileStatus = ChangeListManager.getInstance(project).getStatus(file)
        if (fileStatus in NON_ANNOTATABLE_STATUSES) return null
        val requiresLineTracker = fileStatus != FileStatus.NOT_CHANGED ||
            FileDocumentManager.getInstance().isDocumentUnsaved(document)
        if (
            requiresLineTracker &&
            LineStatusTrackerManager.getInstance(project).getLineStatusTracker(document)?.isOperational() != true
        ) {
            return null
        }

        val revision = vcs.diffProvider?.getCurrentRevision(file)?.asString() ?: return null
        val key = CommitSnapshotKey(document.modificationStamp, file.modificationStamp, revision)
        cache.get(file, key)?.let { return it[line] }
        val cacheVersion = cache.version(file)

        val annotationProvider = vcs.annotationProvider ?: return null
        val annotation = annotationProvider.annotate(file)
        val lines = try {
            currentCoroutineContext().ensureActive()
            val gitAnnotation = annotation as? GitFileAnnotation ?: return null
            val lineNumbers = UpToDateLineNumberProviderImpl(document, project)
            buildLineCommits(
                documentLineCount = document.lineCount,
                toAnnotatedLine = { currentLine ->
                    if (lineNumbers.isLineChanged(currentLine)) -1 else lineNumbers.getLineNumber(currentLine)
                },
            ) { annotatedLine ->
                if (annotatedLine !in 0 until annotation.lineCount) null
                else gitAnnotation.getLineInfo(annotatedLine)?.toGitCommitInfo()
            }
        } finally {
            annotation.close()
        }

        val latestVcs = vcsManager.getVcsFor(file)
        val latestRevision = latestVcs?.diffProvider?.getCurrentRevision(file)?.asString()
        if (
            document.modificationStamp != key.documentStamp ||
            file.modificationStamp != key.fileStamp ||
            latestVcs?.name != GIT_VCS_NAME ||
            latestRevision != key.revision
        ) {
            return null
        }

        if (!cache.replaceIfCurrent(file, cacheVersion, key, lines)) return null
        return lines[line]
    }
}

/** 只接受作者、时间和摘要都完整的 Git 行信息 */
private fun GitFileAnnotation.LineInfo.toGitCommitInfo(): GitCommitInfo? {
    val commitAuthor = author.trim()
    val commitTime = committerDate.toInstant()
    val commitSummary = subject.trim()
    if (commitAuthor.isEmpty() || commitSummary.isEmpty()) return null
    return GitCommitInfo(commitAuthor, commitTime, commitSummary)
}
