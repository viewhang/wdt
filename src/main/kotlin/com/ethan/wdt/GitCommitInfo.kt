package com.ethan.wdt

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 行尾展示使用固定格式，避免随 IDE 语言变化而改变布局 */
private val COMMIT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 表示某一已提交代码行对应的提交信息 */
internal data class GitCommitInfo(
    val author: String,
    val committedAt: Instant,
    val summary: String,
) {
    /** 生成插件统一使用的单行展示文本 */
    fun displayText(zoneId: ZoneId = ZoneId.systemDefault()): String {
        val time = COMMIT_TIME_FORMATTER.format(committedAt.atZone(zoneId))
        return "$author · $time · $summary"
    }
}
