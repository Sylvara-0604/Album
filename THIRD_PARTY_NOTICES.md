# 第三方素材与许可（third-party notices）

本工程除 AndroidX / Compose / Media3（Apache-2.0，走 Gradle 依赖）外，只有一处内嵌的第三方素材：

## 应用图标图形 —— Google Material Design Icons

| 项 | 内容 |
|---|---|
| 素材 | `image`（filled / 黑色 24dp 矢量版，照片图形） |
| 来源仓库 | https://github.com/google/material-design-icons |
| 许可 | **Apache License 2.0**（仓库根目录 `LICENSE`；上游副本见 `docs/third-party/material-design-icons-LICENSE.txt`） |
| 落位 | `app/src/main/res/drawable/ic_launcher_foreground.xml` |
| 变换 | 原图形墨迹在 24 视口内占 `(3,3)-(21,21)`（边长 18）；按 `group scale=2.0 / translate=30` 缩放 → 自适应图标 108 视口内墨迹 **36×36**，居中，落在官方 66×66 安全区内。 |

### 尺寸依据（官方规范）

出处 `developer.android.com/develop/ui/views/launch/icon_design_adaptive`：

1. 图层画布 = **108×108 dp**；
2. 四边各 **18 dp** 为遮罩保留区 ⇒ 中央可视 **72×72**；
3. **66×66** 是"永不被 OEM 形状裁掉"的方框（其内切圆半径 33）。

36×36 的墨迹四角半径 = `36/√2 ≈ 25.5 < 33` ⇒ 任何形状的遮罩都不会切到图形。

## 代码依赖（不内嵌二进制）

| 依赖 | 许可 |
|---|---|
| AndroidX / Jetpack（core、activity、lifecycle、paging） | Apache-2.0 |
| Jetpack Compose（BOM `2026.10.00`、Material 3 `1.5.0-beta01`） | Apache-2.0 |
| Media3（ExoPlayer、media3-ui-compose） | Apache-2.0 |
| Coil 3（图片加载） | Apache-2.0 |
| Kotlin / kotlinx.coroutines | Apache-2.0 |

全部在构建时从 Google Maven / Maven Central 解析，**仓库内不含任何第三方二进制或媒体文件**。
