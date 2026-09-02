package com.ethan.wdt

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 验证行映射、缓存失效代次和同文件查询串行化 */
class GitCommitInfoServiceTest {
    private val firstCommit = GitCommitInfo("Ethan", Instant.EPOCH, "init")
    private val secondCommit = GitCommitInfo("Ada", Instant.EPOCH.plusSeconds(1), "feat: second")

    /** 未提交行必须从不可变快照中排除 */
    @Test
    fun `过滤没有已提交映射的当前行`() {
        val commits = buildLineCommits(
            documentLineCount = 3,
            toAnnotatedLine = { currentLine -> intArrayOf(0, -1, 1)[currentLine] },
            resolveCommit = { annotatedLine -> listOf(firstCommit, secondCommit)[annotatedLine] },
        )

        assertEquals(firstCommit, commits[0])
        assertNull(commits[1])
        assertEquals(secondCommit, commits[2])
    }

    /** 主动失效后，已经在后台生成的旧快照不得重新写回 */
    @Test
    fun `失效代次阻止旧查询写回缓存`() {
        val cache = LineCommitCache<String>()
        val owner = "sample.kt"
        val key = CommitSnapshotKey(1, 1, "a1")
        val version = cache.version(owner)

        cache.invalidate(owner)

        assertFalse(cache.replaceIfCurrent(owner, version, key, mapOf(0 to firstCommit)))
        assertNull(cache.get(owner, key))
    }

    /** 同一文件的多个调用必须依次进入实际查询区段 */
    @Test
    fun `同一键的并发查询不会并行执行`() = runBlocking {
        val mutexes = PerKeyMutex<String>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        List(3) {
            async {
                mutexes.runExclusive("sample.kt") {
                    val current = active.incrementAndGet()
                    maxActive.updateAndGet { previous -> maxOf(previous, current) }
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxActive.get())
    }
}
