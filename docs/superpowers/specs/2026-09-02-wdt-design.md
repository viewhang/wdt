# wdt（Who Did This）设计文档

## 目标

开发一个 IntelliJ IDEA 插件。当主光标移动到 Git 已跟踪文件的某一行时，在该行代码末尾显示最后一次提交的作者、提交时间和提交摘要。

示例：

```text
Ethan · 2026-09-02 14:30 · feat: 添加订单校验
```

## 项目信息

- 展示名：`wdt`
- 全称：`Who Did This`
- 项目名：`wdt`
- 插件 ID：`com.ethan.wdt`
- 目标 IDE：IntelliJ IDEA 2026.2.1
- 开发语言：Kotlin
- 构建脚本：Gradle Kotlin DSL
- Java 版本：25
- Kotlin 版本：2.4.0
- Gradle 版本：9.1.0
- IntelliJ Platform Gradle Plugin：2.x
- 必需插件：内置 `Git4Idea`

## 首版范围

首版只实现当前行提交信息展示：

- 主光标移动到另一行后自动查询
- 在行尾显示作者、提交时间和提交摘要
- 使用系统本地时区，时间格式为 `yyyy-MM-dd HH:mm`
- 使用灰色斜体文本
- 同一编辑器只显示一个提示
- 支持 Git 已跟踪的文本文件

首版不包含：

- 设置页
- 点击跳转到提交详情
- 右键菜单
- 多光标分别展示
- 非 Git 版本控制系统
- 未提交状态提示

## 行为规则

以下情况不显示任何内容：

- 文件不属于 Git 仓库
- 文件未被 Git 跟踪
- 当前行没有对应的已提交版本
- 当前行包含未提交修改
- 无法取得有效提交信息

主光标移动到另一行后立即移除旧提示；同一行内移动不改变现有提示。查询期间不显示加载状态。预期内的无结果不记录错误；实际 VCS 查询异常只写入 IDE 日志，不弹通知。

文档内容变化时立即移除提示、清除当前文件缓存并重新防抖查询。文件保存、Git 仓库修订变化或 VCS 目录映射变化后执行同样处理。

## 架构

### `WdtEditorListener`

负责编辑器生命周期和光标事件：

- 在编辑器创建时绑定监听器
- 在编辑器销毁时释放关联资源
- 只处理 `EditorKind.MAIN_EDITOR` 且具有项目和本地文件的编辑器
- 将主光标位置变化交给控制器

### `WdtController`

负责单个编辑器内的交互编排：

- 主光标进入另一行时立即清除旧 Inlay，同一行内移动不重复查询
- 对连续光标移动进行短暂防抖
- 取消旧请求的等待与展示任务
- 查询返回后校验编辑器、文件、行号和请求序号
- 只将仍然有效的结果交给渲染器
- 为当前文档持有行状态跟踪器，跟踪器就绪后主动刷新

控制器的协程生命周期与编辑器绑定，编辑器销毁后取消任务并移除 Inlay。

### `GitCommitInfoService`

负责读取和缓存 Git 行提交信息：

- 通过 IntelliJ VCS `AnnotationProvider` 获取文件注释
- 将当前编辑器行映射到对应的已提交行
- 从 Git 注释的同步行信息提取作者、日期和提交摘要
- 当前行没有已提交映射时返回无结果
- 修改文件的行状态跟踪器未就绪时不生成或缓存结果
- 按文件缓存逐行提交信息
- 使用 `Dispatchers.IO` 执行阻塞式 VCS 查询
- 同一文件的查询通过按文件互斥锁串行化，避免并发执行重复注释请求
- 文件内容、外部文件、Git 仓库状态或 VCS 映射变化时清除对应缓存
- 将 `FileAnnotation` 转换为不可变快照后立即释放

VCS 查询在后台线程执行，不阻塞编辑器 UI。协程取消会中止等待并传递给支持取消检查的 VCS 调用；若底层阻塞调用尚未退出，按文件互斥锁保证同一文件不会启动第二个并发查询。

### `WdtInlayRenderer`

负责行尾提示的布局与绘制：

- 使用编辑器当前配色方案计算文本颜色
- 使用斜体字体绘制单行文本
- 将 Inlay 关联到前置代码文本
- 不处理查询、缓存或事件

### `GitCommitInfo`

保存作者、提交时间和提交摘要，并生成统一展示文本。

## 数据流

```text
主光标移动
  → 清除旧 Inlay
  → 防抖并发起请求
  → 判断当前文件是否属于 Git 且已跟踪
  → 进入当前文件的互斥查询区段
  → 在 IO 调度器获取或复用文件注释
  → 映射当前行到已提交行
  → 提取作者、时间和摘要
  → 校验请求仍对应当前光标
  → 在 UI 线程创建行尾 Inlay
```

## 缓存与失效

缓存粒度为文件，避免光标逐行移动时反复加载整份 Git 注释。

以下事件使对应缓存失效：

- 文档内容变化
- 文件被外部修改
- Git 当前修订版本变化
- 文件离开当前 Git 映射

缓存使用弱文件键，只持有不可变的逐行提交信息，不持有 `FileAnnotation`，不会延长文件生命周期。控制器在上述事件发生时主动删除当前文件缓存，项目关闭后服务整体释放；不设置额外的定时刷新或备用数据源。

## 并发规则

- 防抖仅用于减少快速移动光标产生的无效查询
- 每个编辑器只保留最新请求
- 同一行内移动主光标不创建新请求
- 同一文件同时最多执行一个 VCS 注释查询
- 后台结果必须携带请求序号
- 仅当编辑器未销毁、文件未变化且主光标仍在原行时才能展示
- Inlay 的创建和释放只在 UI 线程执行

## 错误处理

下列情况视为正常无结果，直接隐藏提示：

- 没有 Git 映射
- 文件未跟踪
- 行未提交
- 注释中找不到对应修订版本

只有 VCS API 抛出的非取消异常写入日志。插件不弹错误气泡，也不使用命令行 Git 作为备用方案。

## 项目结构

```text
wdt/
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
├── gradlew
├── gradlew.bat
└── src/
    ├── main/
    │   ├── kotlin/com/ethan/wdt/
    │   │   ├── WdtEditorListener.kt
    │   │   ├── WdtController.kt
    │   │   ├── GitCommitInfoService.kt
    │   │   ├── WdtInlayRenderer.kt
    │   │   └── GitCommitInfo.kt
    │   └── resources/META-INF/plugin.xml
    └── test/kotlin/com/ethan/wdt/
```

## 验证

自动化验证覆盖：

- 提交信息格式化
- 通过临时 Git 仓库验证已提交行返回并展示正确信息
- 通过临时 Git 仓库验证未提交行和未跟踪文件不返回信息
- 同一文件并发请求不会并行执行 VCS 注释
- 快速移动光标时旧请求结果被丢弃，同一行内移动不重复查询
- 文档、外部文件、仓库或 VCS 映射变化时缓存失效
- 非主编辑器不创建控制器
- 编辑器销毁时任务和 Inlay 被释放

构建验证执行：

```text
test
verifyPluginProjectConfiguration
verifyPlugin
```

手动验证使用临时 Git 仓库，检查浅色和深色主题下的显示效果，并确认移动光标、修改文件和切换提交后没有残留提示。

## 参考资料

- [IntelliJ Platform 2026 API 变更](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html)
- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [Gradle Java 兼容性](https://docs.gradle.org/current/userguide/compatibility.html)
- [Kotlin Gradle 兼容性](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Kotlin 支持配置](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html)
- [Inlay Hints](https://plugins.jetbrains.com/docs/intellij/inlay-hints.html)
- [Coroutine Dispatchers](https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html)
- [Execution Contexts](https://plugins.jetbrains.com/docs/intellij/execution-contexts.html)
- [JetBrains AnnotateToggleAction](https://github.com/JetBrains/intellij-community/blob/master/platform/vcs-impl/src/com/intellij/openapi/vcs/actions/AnnotateToggleAction.java)
