# Album

A modern Android gallery — an independent, from-scratch re-implementation of the interaction and motion design of Sony's "Album" app, written in **Kotlin + Jetpack Compose (Material 3)**.

Timeline grid with pinch-to-change-columns (1–7, remembered across restarts), a banner that rotates on a timer, folders, a full-screen viewer with anchor-point zoom, video playback (Media3), free-form cropping and a details sheet. Everything is built on official AndroidX / Compose / Media3 components — no custom GL engine, no third-party SDKs.

> The *behaviour* is modelled on Sony Album 6.0 / 7.0 / 8.0 / 9.9 as observed on-device. **No Sony source code, resource, icon, font or asset was copied** — see [Provenance and affiliation](#provenance-and-affiliation).

## Features

| | |
|---|---|
| **Timeline grid** | 1–7 columns, pinch to change (continuous interpolation, not a page jump), per-month headers, a 2×2 feature cell at each section start, no rounded corners — matching the original layout formulas |
| **Pinch** | Column count follows your fingers; the photo under your fingers stays put; focus item rotates / follows horizontally below 3.1 columns; release settles with a spring, never overshoots |
| **Banner** | Static image, 16:9, cross-fade every 5 s (off/5 s/10 s/1 min in settings), parallax + scroll fade, day caption bottom-left |
| **Viewer** | Horizontal pager, anchor-point pinch zoom, double-tap to 1:1, soft/hard zoom limits with spring-back, pull-down to dismiss, full-size decode on demand |
| **Video** | Media3 ExoPlayer, **never autoplays**, four-item control bar (play/pause, progress, time, buffering), thumbnail stays on screen until the first frame is actually composited |
| **Folders** | Full-bleed cover rows, name + storage-type icon + item count, folders sorted by name; multi-select inside a folder |
| **Multi-select** | Long-press to start, bottom capsule extends horizontally with a spring, share / delete / new folder |
| **Crop** | Free-form only (no fixed ratios): 8 handles, pan inside, pinch to scale, saved back through `MediaStore` (`IS_PENDING`) |
| **Theming** | Material 3 dynamic colour, light / dark / follow-system, edge-to-edge with correct system-bar icon contrast |
| **Settings** | Theme, banner interval, page-transition tempo, viewer close animation (grid slides over / viewer slides back), cache size + clear |

## Requirements

- Android **13 (API 33)** or newer
- A device with photo/video media (`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`; no `MANAGE_EXTERNAL_STORAGE`, no `INTERNET`)

## Install

Build it yourself (below), or grab the APK from the [Releases](https://github.com/Sylvara-0604/Album/releases) page.

The APK is signed with a development key, so Play Protect may warn you — expected for a self-built side-loaded app.

## Tech stack

| Area | What is used |
|---|---|
| Language | **Kotlin** `2.4.20` (Kotlin support is built into AGP 9; the Compose compiler plugin is applied explicitly) |
| UI | **Jetpack Compose** — Compose BOM `2026.10.00`, **Material 3** `1.5.0-beta01` (Expressive: `HorizontalFloatingToolbar`, motion schemes) |
| Navigation | **Navigation 3** `1.2.0` (`NavDisplay` + own back stack) |
| Images | **Coil 3** (`coil-compose`) + a custom `Fetcher` over `ContentResolver.loadThumbnail()` |
| Video | **Media3** `1.11.1` (`ExoPlayer`, `media3-ui-compose` `ContentFrame` with `TextureView`) |
| Paging | **Paging 3** `3.5.3` over `MediaStore` pokeys with `QUERY_ARG_LIMIT` / `OFFSET` |
| Async | **kotlinx.coroutines** `1.11.0`, `StateFlow` |
| Build | **Gradle 9.8.2** + **Android Gradle Plugin 9.4.1**; `compileSdk 37` (minor 2) / `minSdk 33` / `targetSdk 37`; Java 27 toolchain |
| Release packaging | **R8** code shrinking + **resource shrinking** (release APK ≈ 4.2 MB) |
| Tests | JVM unit tests (**80**, JUnit 4 + Robolectric where a resource is needed), lint, JaCoCo report |

No DI framework, no analytics, no network permission, no vendored binaries.

## Getting the source and building

```bash
git clone https://github.com/Sylvara-0604/Album.git
cd Album
```

Prerequisites:

| Tool | Version | Note |
|---|---|---|
| JDK | **27** (Temurin 27+35 tested) | `JAVA_HOME` must point to it |
| Android SDK | **platform 37** (`compileSdk 37` + minor 2 ⇒ `android-37.2`), build-tools 37.x | `ANDROID_HOME` / `ANDROID_SDK_ROOT` |
| Gradle | **9.8.2** | This repository **has no Gradle wrapper** on purpose — install Gradle 9.8.2 and call it directly |

```bash
export JAVA_HOME=/path/to/jdk-27
export ANDROID_HOME=/path/to/android-sdk

gradle :app:testDebugUnitTest :app:lintDebug    # gate (no device needed)
gradle :app:assembleRelease                     # release APK -> app/build/outputs/apk/release/
```

**Signing.** The release build is signed only if you provide `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/your.jks
storePassword=***
keyAlias=***
keyPassword=***
```

Without it the release build is produced **unsigned** — install it after signing with `apksigner`, or just use `:app:assembleDebug`.

`benchmark/` holds a `baselineprofile` module; it needs a connected device and is optional.

## Provenance and affiliation

- This is an **independent re-implementation**. The interaction/motion design follows the behaviour of **Sony "Album"** (`com.sonyericsson.album`, versions 6.0 / 7.0 / 8.0 / 9.9) as observed on-device, including its layout formulas, animation timings and gesture rules. **No Sony source file, layout, drawable, string, icon, font or other resource is included in this repository or in the APK** — the Kotlin/Compose implementation was written from scratch.
- This project is **not affiliated with, endorsed by, or sponsored by Sony** (or Sony Mobile / Sony Ericsson).
- "Sony", "Xperia" and "Album" are trademarks of their respective owners. They appear here **nominatively** — to say what this software's behaviour is modelled on — and do not imply any endorsement.
- The reverse-engineering notes used during development are **not** part of this repository.
- **Application ID.** The id is `com.example.album`, a placeholder. If you plan to distribute this on a store or inside a ROM, change **both** `namespace` and `applicationId` in `app/build.gradle.kts` to your own reverse-domain id first.

## Third-party components

- **App icon glyph** — `image` from [Google Material Design Icons](https://github.com/google/material-design-icons), Apache-2.0. Transform and sizing rationale: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md); upstream licence copy: [`docs/third-party/material-design-icons-LICENSE.txt`](docs/third-party/material-design-icons-LICENSE.txt). It is the only embedded third-party asset.
- **Libraries** — AndroidX / Compose / Media3 / Coil / kotlinx.coroutines, all Apache-2.0, resolved from Google Maven and Maven Central at build time. No third-party binaries are vendored.
- **No bundled media** — no stock photos, fonts, sounds or sample images ship with the code or the APK.
- **Permissions** — photo/video read access only (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`). There is **no `INTERNET` permission**.

## License

[Apache License 2.0](LICENSE) © 2026 Sylvara-0604. See also [`NOTICE`](NOTICE).

The software is provided **"AS IS", without warranty of any kind**, express or implied. See `LICENSE` §7–8 for the full disclaimer.

---

## 中文说明

用 **Kotlin + Jetpack Compose（Material 3）** 从零重写的一款现代相册：时间线网格（1–7 列双指换列、按月分组、每段首格 2×2）、定时轮换的 banner、文件夹、全屏查看器（锚手指缩放 / 双击 1:1 / 下拉关闭）、Media3 视频播放（**不自动播放**）、自由裁剪、详情面板。

**与原版的关系**：交互与动画的**行为**按 Sony Album 6.0 / 7.0 / 8.0 / 9.9 在真机上的表现还原（尺寸公式、时序、曲线、手势判据）；**没有复制任何 Sony 的代码、资源、图标或字体**，全部为独立实现。本项目**与 Sony 无任何隶属、赞助或背书关系**，商标仅作指称性使用。

**许可**：[Apache License 2.0](LICENSE)；第三方素材与依赖见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 与 [`NOTICE`](NOTICE)。
