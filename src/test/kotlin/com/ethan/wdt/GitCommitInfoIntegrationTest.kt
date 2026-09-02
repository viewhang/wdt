package com.ethan.wdt

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** 使用真实临时 Git 仓库验证 IntelliJ 注释 API 的端到端行为 */
class GitCommitInfoIntegrationTest : BasePlatformTestCase() {
    private lateinit var repositoryPath: Path
    private lateinit var repositoryRoot: VirtualFile

    /** 初始化独立仓库并提交包含两行内容的文本文件 */
    override fun setUp() {
        super.setUp()
        repositoryPath = Files.createTempDirectory("wdt-")
        runGit("init")
        Files.writeString(repositoryPath.resolve("tracked.txt"), "first\nsecond\n")
        runGit("add", "tracked.txt")
        runGit(
            "-c", "user.name=Ethan",
            "-c", "user.email=ethan@example.com",
            "commit", "-m", "feat: initial",
        )
        repositoryRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repositoryPath),
        )
        PsiTestUtil.addContentRoot(module, repositoryRoot)
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(
            listOf(VcsDirectoryMapping(repositoryPath.toString(), "Git")),
        )
        VfsUtil.markDirtyAndRefresh(false, true, true, repositoryRoot)
        waitForChangeListUpdate()
    }

    /** 删除测试创建的真实临时仓库 */
    override fun tearDown() {
        try {
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(emptyList())
            PsiTestUtil.removeContentEntry(module, repositoryRoot)
            repositoryPath.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** 已提交行必须返回真实作者和提交摘要 */
    fun testCommittedLineReturnsCommitInfo() {
        val file = requireNotNull(repositoryRoot.findChild("tracked.txt"))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))

        val info = runBlocking {
            project.service<GitCommitInfoService>().findCommitInfo(file, document, 0)
        }

        assertEquals("Ethan", info?.author)
        assertEquals("feat: initial", info?.summary)
    }

    /** 本地修改覆盖的行不得沿用修改前的提交信息 */
    fun testLocallyModifiedLineReturnsNull() {
        val file = requireNotNull(repositoryRoot.findChild("tracked.txt"))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(0, document.getLineEndOffset(0), "changed")
        }
        FileDocumentManager.getInstance().saveDocument(document)
        waitForChangeListUpdate()

        val service = project.service<GitCommitInfoService>()
        val infoBeforeTracker = runBlocking {
            service.findCommitInfo(file, document, 1)
        }
        assertNull(infoBeforeTracker)

        val trackerManager = LineStatusTrackerManager.getInstance(project)
        trackerManager.requestTrackerFor(document, this)
        try {
            waitForOperationalLineTracker(document)
            waitForModifiedLine(document, 0)

            val modifiedLineInfo = runBlocking {
                service.findCommitInfo(file, document, 0)
            }
            val unchangedLineInfo = runBlocking {
                service.findCommitInfo(file, document, 1)
            }

            assertNull(modifiedLineInfo)
            assertEquals("Ethan", unchangedLineInfo?.author)
        } finally {
            trackerManager.releaseTrackerFor(document, this)
        }
    }

    /** 未跟踪文件不得触发 Git 注释结果 */
    fun testUntrackedFileReturnsNull() {
        Files.writeString(repositoryPath.resolve("untracked.txt"), "new\n")
        val file = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repositoryPath.resolve("untracked.txt")),
        )
        waitForChangeListUpdate()
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))

        val info = runBlocking {
            project.service<GitCommitInfoService>().findCommitInfo(file, document, 0)
        }

        assertNull(info)
    }

    /** 只在测试中调用 Git 构造真实仓库，生产代码不执行命令行 Git */
    private fun runGit(vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", *arguments))
            .directory(repositoryPath.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    /** 等待变更列表刷新，避免文件状态尚未同步造成竞态 */
    private fun waitForChangeListUpdate() {
        val completed = CountDownLatch(1)
        com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
            .invokeAfterUpdate(false, completed::countDown)
        assertTrue(completed.await(10, TimeUnit.SECONDS))
    }

    /** 等待行状态跟踪器完成基础版本加载 */
    private fun waitForOperationalLineTracker(document: Document) {
        PlatformTestUtil.waitWithEventsDispatching(
            "line status tracker did not become operational",
            {
                LineStatusTrackerManager.getInstance(project)
                    .getLineStatusTracker(document)
                    ?.isOperational() == true
            },
            10,
        )
    }

    /** 等待行状态跟踪器识别指定文档行的本地修改 */
    private fun waitForModifiedLine(document: Document, line: Int) {
        PlatformTestUtil.waitWithEventsDispatching(
            "line status tracker did not detect the modified line",
            {
                LineStatusTrackerManager.getInstance(project)
                    .getLineStatusTracker(document)
                    ?.isLineModified(line) == true
            },
            10,
        )
    }

}
