package com.ethan.wdt

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** 验证提交信息使用固定展示格式和显式时区 */
class GitCommitInfoTest {
    /** 指定时区后必须生成稳定且不受系统设置影响的文本 */
    @Test
    fun `按指定时区格式化提交信息`() {
        val commit = GitCommitInfo(
            author = "Ethan",
            committedAt = Instant.parse("2026-09-02T06:30:00Z"),
            summary = "feat: 添加订单校验",
        )

        assertEquals(
            "Ethan · 2026-09-02 14:30 · feat: 添加订单校验",
            commit.displayText(ZoneId.of("Asia/Singapore")),
        )
    }
}
