# Who Did This

English | [简体中文](readme-CN.md)

**Who Did This** is an IntelliJ IDEA plugin that shows the author, time, and summary of the last Git commit affecting the line under the primary caret, right at the end of that line.

```text
Ethan · 2026-09-02 14:30 · feat: add order validation
```

## Features

- Automatically retrieves commit information when the primary caret moves to another line
- Displays the author, commit time in your local time zone, and commit summary at the end of the line
- Refreshes when the document changes, the file is saved, or the Git repository changes
- Discards outdated results when the caret moves quickly
- Adapts to the IDE theme, displaying hints in gray italic text

No hint is shown when:

- The file is outside a Git repository or is untracked
- The current line has no corresponding committed version
- The current line contains uncommitted changes
- Complete commit information is unavailable

## Requirements

- IntelliJ IDEA 2024.3 or later
- JDK 21 for building the plugin

## Build

```bash
./gradlew buildPlugin
```

The plugin archive is generated at `build/distributions/wdt-<version>.zip`.

To build against a locally installed IntelliJ IDEA:

```bash
./gradlew buildPlugin -PintellijPlatform.localIdePath="/path/to/IntelliJ IDEA.app"
```

## Test

```bash
./gradlew test
```

## Installation

1. Build the plugin archive
2. Open `Settings | Plugins` in IntelliJ IDEA
3. Click the gear icon and select `Install Plugin from Disk...`
4. Select `build/distributions/wdt-<version>.zip`
5. Restart IntelliJ IDEA to activate the plugin
6. Open a Git-tracked file and move the primary caret to the line you want to inspect

## Current Limitations

- Git only
- Follows only the primary caret
- Does not display uncommitted status
- No settings page, navigation to commit details, or context menu

## License

[MIT](LICENSE)
