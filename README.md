# MATOlog

**A local-only event recorder for Android.** It quietly logs which app you're using, and lets you
write down anything by hand,can help you review what you did throughout the day

**一个只存在本机的安卓事件记录本。** 它按分钟记下你在用哪个应用，也让你随手写一条，帮助你复盘一天中都做了什么

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%2024%2B-green.svg)
![Language](https://img.shields.io/badge/Language-Java-orange.svg)

---
## Features / 主要功能
* 手动或自动记录
* 亮|暗色主题
* 按日期或关键词检索事件
* 数据的导出与导入
* 应用白名单功能
---
## Build / 构建

Requirements:

- **JDK 17**
- **Android SDK** with platform 34 and build-tools 34.0.0
- **Gradle 8.0** (the wrapper will fetch it; it also works offline against a local copy)

```bash
# point Gradle at your SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The release signing config reads its passwords from `MATO_STORE_PASSWORD` and
`MATO_KEY_PASSWORD` in a `gradle.properties` **one level above the project** (outside the
repository), so no credentials ever enter version control. To build a debug APK without any
signing setup:

```bash
./gradlew :app:assembleDebug
```

> **Note on non-ASCII paths:** if the project path contains non-ASCII characters, Gradle needs
> `android.overridePathCheck=true` (already set) and `-Dfile.encoding=UTF-8`.

---
## Tech / 技术

- **Pure Android framework.** Java 17, system widgets only. No AndroidX, no third-party UI library.
- The single compile-time dependency is Shizuku (`compileOnly`), and it degrades to nothing when
  Shizuku isn't installed.
- **minSdk 24** (Android 7.0) · **targetSdk 34**
- Storage: SQLite (`databases/matolog.db`)
- No `INTERNET` permission, no analytics, no crash reporting
---
### Project layout

```
MATOlog/
├── app/src/main/
│   ├── java/com/MATO/log/
│   │   ├── data/      Event · Attachment · DbHelper (SQLite)
│   │   ├── rec/       Foreground state machine, session tracking, usage reader
│   │   ├── service/   Foreground recording service, boot receiver
│   │   ├── ui/        Activities, the day/month/year views, the drill-down host
│   │   ├── util/      Date/time, JSON codec, crypto, attachments, preferences
│   │   └── cap/       Cache store
│   ├── res/           Layouts, styles, drawables — all hand-written
│   └── AndroidManifest.xml
└── tools/             Offline regression checks (plain Java, run on the JVM)
```
---
## Contributing / 参与

Issues and pull requests are welcome. Two things to know before you dive in:

1. **The source comments and all internal documentation are in Chinese.** The code itself is
   ordinary Java.
2. **This project was developed with substantial AI assistance**

---

## License / 许可

**AGPL-3.0** — see [LICENSE](LICENSE).

---
feedback|意见反馈
* email xing.archive@icloud.com
