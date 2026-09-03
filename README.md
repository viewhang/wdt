# Who Did This

**Who Did This** 是一个 IntelliJ IDEA 插件，用于在主光标所在代码行末尾显示该行最后一次 Git 提交的作者、时间和摘要

```text
Ethan · 2026-09-02 14:30 · feat: 添加订单校验
```

## 功能

- 主光标移动到其他代码行后自动查询提交信息
- 在代码行末尾显示作者、本地时间和提交摘要
- 修改文档、保存文件或 Git 仓库变化后自动刷新
- 快速移动光标时丢弃过期结果
- 自动适配 IDE 主题，以灰色斜体文本显示提示

以下情况不会显示提示：

- 文件不属于 Git 仓库或尚未被跟踪
- 当前行没有对应的已提交版本
- 当前行包含未提交修改
- 无法取得完整的提交信息

## 环境要求

- IntelliJ IDEA 2024.3 或更高版本
- 构建环境使用 JDK 21

## 构建

```bash
./gradlew buildPlugin
```

插件包生成在 `build/distributions/wdt-<版本号>.zip`

如需使用本机已安装的 IntelliJ IDEA 构建：

```bash
./gradlew buildPlugin -PintellijPlatform.localIdePath="/path/to/IntelliJ IDEA.app"
```

## 测试

```bash
./gradlew test
```

## 安装

1. 构建插件包
2. 打开 IntelliJ IDEA 的 `Settings | Plugins`
3. 点击齿轮图标并选择 `Install Plugin from Disk...`
4. 选择 `build/distributions/wdt-<版本号>.zip`
5. 打开 Git 已跟踪文件，将主光标移动到需要查看的代码行

## 当前限制

- 仅支持 Git
- 仅跟随主光标
- 不显示未提交状态
- 不提供设置页、点击跳转或右键菜单

## License

[MIT](LICENSE)
