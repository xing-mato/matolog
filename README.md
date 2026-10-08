# MATOlog

**A local-only event recorder for Android.** It quietly logs which app you're using, and lets you
write down anything by hand. Your data never leaves the device.

**一个只存在本机的安卓事件记录本。** 它按分钟记下你在用哪个应用，也让你随手写一条。
数据永远不出这台设备。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%2024%2B-green.svg)
![Language](https://img.shields.io/badge/Language-Java-orange.svg)

---

## Why

Most "screen time" apps want an account, a cloud, and a network permission. MATOlog doesn't have
any of those. It's a small tool for one question — *what did I actually do today?* — and it answers
it from a SQLite file on your own phone.

---

## Features / 主要功能

| English | 中文 |
| --- | --- |
| **Automatic recording** — samples the foreground app and merges it into sessions | **自动记录** —— 按分钟采样前台应用，按应用切换切成一段一段 |
| **Manual entries** — a timestamp and a note, nothing more | **手动记录** —— 一个时间加一句话，没有多余的东西 |
| **Browse by day / month / year** — drill down from a year view to a single day | **按日 / 月 / 年检索** —— 从年历一路点进某一天 |
| **Keyword search** | **关键词检索** |
| **Attachments** — copy into the app, or just reference the original file | **附件** —— 存进应用内，或只引用原文件 |
| **Export / import JSON** — optionally encrypted | **导出 / 导入 JSON** —— 可选加密 |
| **App lock** — 4–6 digit passcode, stored as a salted hash only | **应用锁** —— 4–6 位数字口令，只存加盐哈希 |
| **App list (whitelist)** — exclude the apps you don't want recorded | **应用名单（白名单）** —— 排除掉不想被记录的应用 |
| **Dark mode** — follow system, always dark, always light | **深色模式** —— 跟随系统 / 始终深色 / 始终浅色 |
| **5 languages** — 简体中文 · 繁體中文 · English · 日本語 · 한국어 | **五种语言** |
| **Error log** — exportable, and only reachable once something has actually failed | **报错日志** —— 可导出；**没出过问题时根本点不动** |

---

## Privacy / 隐私

**The app declares no `INTERNET` permission.** That is not a promise — it is a fact you can check
yourself, in one command:

```bash
aapt2 dump permissions MATOlog-1.0.0-release.apk
```

There is no account, no server, no analytics, no crash reporting service. The only copy of your
data is `databases/matolog.db` inside the app's private storage.

### The full permission list, and why each one exists

| Permission | Used for | Notes |
| --- | --- | --- |
| `PACKAGE_USAGE_STATS` | Reading which app is in the foreground | The core of automatic recording. **Must be granted by hand** in *Settings → Special access → Usage access*; there is no runtime dialog for it. |
| `QUERY_ALL_PACKAGES` | Listing installed apps | So you can pick which ones to exclude. |
| `POST_NOTIFICATIONS` | The persistent notification | Required by Android 13+ for a foreground service. |
| `FOREGROUND_SERVICE`<br>`FOREGROUND_SERVICE_SPECIAL_USE` | Long-running background recording | Keeps the system from killing the recorder. |
| `RECEIVE_BOOT_COMPLETED` | Restart after reboot | The recorder comes back up on its own. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Asking to be exempted from battery optimisation | Optional. Without it the app still works, it just gets killed more often. |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | Exporting to the Downloads folder | Android 9 and below only. |
| `moe.shizuku.manager.permission.API_V23` | Optional Shizuku integration | Only if you have Shizuku installed. Without it, MATOlog is an ordinary app. |

**Deliberately absent:** `INTERNET`, `SYSTEM_ALERT_WINDOW`, `READ_LOGS`, `READ_SMS`, `READ_CONTACTS`.

---

## Download / 下载

Grab the APK from the [**Releases**](../../releases) page — not from the repository, which holds
source only.

请到 [**Releases**](../../releases) 页下载 APK。仓库里只放源码，不放安装包。

```bash
adb install -r MATOlog-1.0.0-release.apk
```

> **Version numbering notice.** Builds `1.0`–`1.3.5` were a **closed internal-test line** signed
> with an older key. Public development restarts at **1.0.0** with a new signing key, so the
> internal builds cannot be upgraded in place — uninstall first. Export your data to JSON before
> you do; the encryption key material travels inside the exported file, so importing it into a
> fresh install needs no passphrase.
>
> **版本号说明。** `1.0`–`1.3.5` 是**已封存的内测线**，用旧密钥签名。公开线从 **1.0.0**
> 重新起算、换用新密钥，因此**与内测版签名不同、不能覆盖安装**。升级前请先导出 JSON；
> 加密导出的密钥材料随文件走，装好新包后直接导入即可，不需要任何口令。

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

## Status / 现状

**Tested on:** vivo Z1 (Android 9) and vivo V2425A (OriginOS 6 / Android 16). **Other vendors'
ROMs are untested** — background-killing policies differ wildly, so reports are welcome.

There are 200+ offline regression checks under `tools/` that run on the JVM without a device.
Known gaps are tracked in the project's handover document (Chinese).

---

## Contributing / 参与

Issues and pull requests are welcome. Two things to know before you dive in:

1. **The source comments and all internal documentation are in Chinese.** The code itself is
   ordinary Java.
2. **This project was developed with substantial AI assistance**, and the handover document says
   so plainly. Judge it by the code and the tests.

---

## License / 许可

**AGPL-3.0** — see [LICENSE](LICENSE).

You are free to use, study, modify and redistribute this software. The one condition that matters:
**if you distribute a modified version — or run one as a network service — you must release your
source under the same license.** Derivatives stay open.

你可以自由使用、研究、修改和再分发。唯一要紧的条件是：**如果你分发了修改版，或把它作为网络
服务运行，你必须以同样的许可公开你的源码。** 衍生作品保持开源。
