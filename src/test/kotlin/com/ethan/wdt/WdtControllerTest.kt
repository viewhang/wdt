package com.ethan.wdt

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** 验证控制器只展示最新行并正确管理查询与资源生命周期 */
class WdtControllerTest : BasePlatformTestCase() {
    /** 递增序号必须使所有旧请求立即失效 */
    fun testOnlyLatestRequestRemainsCurrent() {
        val state = WdtRequestState()

        val first = state.next()
        val second = state.next()

        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
        state.invalidate()
        assertFalse(state.isCurrent(second))
    }

    /** 查询无结果时不得创建行尾元素 */
    fun testNullResultDoesNotCreateInlay() {
        myFixture.configureByText("Sample.kt", "val answer = 42")
        val completed = CountDownLatch(1)
        val controller = WdtController(
            editor = myFixture.editor,
            loadCommitInfo = { _, _, _ ->
                completed.countDown()
                null
            },
            invalidateFile = {},
            debounceMillis = 0,
        )

        try {
            waitForLatch(completed)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertTrue(myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(0).isEmpty())
        } finally {
            Disposer.dispose(controller)
        }
    }

    /** 主光标在同一逻辑行内移动时不得清除提示或重新查询 */
    fun testCaretMovementWithinSameLineDoesNotReload() {
        myFixture.configureByText("Sample.kt", "val answer = 42")
        val calls = AtomicInteger()
        val completed = CountDownLatch(1)
        val controller = WdtController(
            editor = myFixture.editor,
            loadCommitInfo = { _, _, _ ->
                calls.incrementAndGet()
                completed.countDown()
                GitCommitInfo("Ethan", Instant.EPOCH, "init")
            },
            invalidateFile = {},
            debounceMillis = 0,
        )

        try {
            waitForLatch(completed)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val originalInlay = myFixture.editor.inlayModel
                .getAfterLineEndElementsForLogicalLine(0)
                .single()
            myFixture.editor.caretModel.moveToOffset(5)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(1, calls.get())
            assertSame(
                originalInlay,
                myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(0).single(),
            )
        } finally {
            Disposer.dispose(controller)
        }
    }

    /** 较早完成的旧行结果不得覆盖当前行提示 */
    fun testStaleResultDoesNotReplaceCurrentLineInlay() {
        myFixture.configureByText("Sample.kt", "first\nsecond")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val controller = WdtController(
            editor = myFixture.editor,
            loadCommitInfo = { _, _, line ->
                withContext(Dispatchers.IO) {
                    if (line == 0) {
                        firstStarted.countDown()
                        releaseFirst.await(2, TimeUnit.SECONDS)
                        GitCommitInfo("Old", Instant.EPOCH, "old")
                    } else {
                        secondCompleted.countDown()
                        GitCommitInfo("New", Instant.EPOCH, "new")
                    }
                }
            },
            invalidateFile = {},
            debounceMillis = 0,
        )

        try {
            waitForLatch(firstStarted)
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(1))
            waitForLatch(secondCompleted)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            releaseFirst.countDown()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertTrue(myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(0).isEmpty())
            assertEquals(1, myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(1).size)
        } finally {
            releaseFirst.countDown()
            Disposer.dispose(controller)
        }
    }

    /** 文档内容变化时必须清缓存并重新查询当前行 */
    fun testDocumentChangeInvalidatesCacheAndReloads() {
        myFixture.configureByText("Sample.kt", "value")
        val loads = AtomicInteger()
        val invalidations = AtomicInteger()
        val completed = CountDownLatch(2)
        val controller = WdtController(
            editor = myFixture.editor,
            loadCommitInfo = { _, _, _ ->
                loads.incrementAndGet()
                completed.countDown()
                null
            },
            invalidateFile = { invalidations.incrementAndGet() },
            debounceMillis = 0,
        )

        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "initial query did not finish",
                { completed.count == 1L },
                5,
            )
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "x")
            }
            waitForLatch(completed)
            assertEquals(2, loads.get())
            assertEquals(1, invalidations.get())
        } finally {
            Disposer.dispose(controller)
        }
    }

    /** VCS 映射变化时必须清缓存并重新查询当前行 */
    fun testVcsMappingChangeInvalidatesCacheAndReloads() {
        myFixture.configureByText("Sample.kt", "value")
        val loads = AtomicInteger()
        val invalidations = AtomicInteger()
        val completed = CountDownLatch(2)
        val controller = WdtController(
            editor = myFixture.editor,
            loadCommitInfo = { _, _, _ ->
                loads.incrementAndGet()
                completed.countDown()
                null
            },
            invalidateFile = { invalidations.incrementAndGet() },
            debounceMillis = 0,
        )

        try {
            PlatformTestUtil.waitWithEventsDispatching(
                "initial query did not finish",
                { completed.count == 1L },
                5,
            )
            project.messageBus
                .syncPublisher(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED)
                .directoryMappingChanged()
            waitForLatch(completed)
            assertEquals(2, loads.get())
            assertEquals(1, invalidations.get())
        } finally {
            Disposer.dispose(controller)
        }
    }

    /** 释放控制器时必须同步移除 Inlay */
    fun testDisposeClearsInlay() {
        myFixture.configureByText("Sample.kt", "value")
        val completed = CountDownLatch(1)
        val controller = WdtController(
            editor = myFixture.editor,
            loadCommitInfo = { _, _, _ ->
                completed.countDown()
                GitCommitInfo("Ethan", Instant.EPOCH, "init")
            },
            invalidateFile = {},
            debounceMillis = 0,
        )

        try {
            waitForLatch(completed)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(1, myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(0).size)
        } finally {
            Disposer.dispose(controller)
        }

        assertTrue(myFixture.editor.inlayModel.getAfterLineEndElementsForLogicalLine(0).isEmpty())
    }

    /** 释放控制器时必须取消尚未完成的查询 */
    fun testDisposeCancelsPendingQuery() {
        myFixture.configureByText("Sample.kt", "value")
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val controller = WdtController(
            editor = myFixture.editor,
            loadCommitInfo = { _, _, _ ->
                try {
                    started.countDown()
                    awaitCancellation()
                } finally {
                    cancelled.countDown()
                }
            },
            invalidateFile = {},
            debounceMillis = 0,
        )

        try {
            waitForLatch(started)
        } finally {
            Disposer.dispose(controller)
        }
        waitForLatch(cancelled)
    }

    /** 等待后台查询时持续派发 EDT 事件，避免测试线程阻塞控制器协程 */
    private fun waitForLatch(latch: CountDownLatch) {
        PlatformTestUtil.waitWithEventsDispatching(
            "wdt query did not finish",
            { latch.count == 0L },
            5,
        )
    }
}
