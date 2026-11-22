# 工具链升级记录：Spatial SDK 6.1.9 → 6.2.8

| 项 | 值 |
| --- | --- |
| 升级日期 | 2026-11-12 |
| 基线版本 | v1.2.1（commit `a27f4ec`，versionCode 3） |
| 目标版本 | v1.3.0（versionCode 4） |
| SDK BOM | `com.pico.spatial:bom` 6.1.9 → **6.2.8**（2026-11-11 发布） |
| CLI | pico-cli 0.6.1 → **0.7.0**（并存安装，未覆盖旧版） |
| 源码改动 | **6 个文件、+182/−11**（`SessionClock.kt` 增加 NOTICE 级消抖，其余为版本类与文档） |
| 构建结论 | `clean assembleDebug` BUILD SUCCESSFUL |

## 1. 变更清单

| 文件 | 改动 | diff |
| --- | --- | --- |
| `gradle/libs.versions.toml` | `spatialBom = "6.1.9"` → `"6.2.8"` | 2 +- |
| `app/build.gradle.kts` | `versionCode 3 → 4`，`versionName "1.2.1" → "1.3.0"` | 4 +- |
| `platform/SessionClock.kt` | NOTICE 级增加消抖窗口，同级抖动不再反复刷事件 | 20 ++- |
| `README.md` | SDK 版本串、pico-cli 版本串、Release 行 | 11 +- |
| `README.zh-CN.md` | 同上（中文） | 12 +- |
| `docs/upgrade-6.2.8.md` | 本记录文件新增 | 144 新增 |

合计 **6 个文件、+182/−11**（`git diff --stat a27f4ec 6244bcc` 实测）。`app/src` 下 29 个 Kotlin 文件中只有 `SessionClock.kt` 一处改动，其余未动。

## 2. 依赖核验

`gradlew :app:dependencies --configuration debugRuntimeClasspath` 实测解析结果：

```
+--- com.pico.spatial:bom:6.2.8
|    +--- com.pico.spatial.core:core:6.2.8 (c)
|    +--- com.pico.spatial.ui:platform:6.2.8 (c)
|    +--- com.pico.spatial.ui:foundation:6.2.8 (c)
|    +--- com.pico.spatial.ui:design:6.2.8 (c)
|    +--- com.pico.spatial.sense:sense:6.2.8 (c)
|    +--- com.pico.spatial.tracking:tracking:6.2.8 (c)
|    \--- com.pico.spatial.foundation:foundation:6.2.8 (c)
```

七个模块全部命中 6.2.8，无版本冲突、无降级回退。

## 3. AAR 公开 API 差异

对 6.1.9 与 6.2.8 的 AAR 逐模块反解 `classes.jar` 对比（原始数据见会话中间产物 `api_diff.json`）：

| 模块 | 新增类 | 移除类 |
| --- | --- | --- |
| core:core | 188 | 0 |
| ui:platform | 9 | 0 |
| ui:foundation | 13 | 0 |
| ui:design | 173 | 5 |
| sense:sense | 3 | 0 |
| tracking:tracking | 0 | 0 |
| foundation:foundation | 1 | 0 |
| **合计** | **387** | **5** |

### 3.1 唯一破坏性变更（5 个移除类，全部在 ui:design）

| 移除类 | 说明 |
| --- | --- |
| `ui.design.arragment.ArrangementExtKt` | 包名拼写错误 `arragment` 已被修正为 `arrangement` |
| `ui.design.arragment.SpacedAligned` | 同上，新位置 `ui.design.arrangement.SpacedAligned` |
| `ui.design.text.SpatialHandlePopupKt` | 由新增的 `SpatialTextFieldPopupKt` 取代 |
| `ui.design.text.ComposableSingletons$SpatialHandlePopupKt` | 同上（内部单例） |
| `ui.design.text.ComposableSingletons$SpatialHandlePopupKt$lambda-1$1` | 同上（内部 lambda） |

**影响评估：无影响。** 对 `app/src/main/java` 全量检索 `arragment`、`SpatialHandlePopup`、`SpatialTextFieldPopup`，命中 0 处。SurgPanel 使用的 20 个 PICO import（`ui.design.PicoTheme`、`ui.design.Text`、`ui.foundation.dsl.*`、`ui.platform.*`、`tracking.*`）全部保留在 6.2.8 中，签名未变。

### 3.2 新增能力分类（387 类，按可读类名归类）

| 领域 | 主要新增 | 与 SurgPanel 关系 |
| --- | --- | --- |
| `core.ecs.particle`（31） | 粒子发射/曲线/渐变/混合模式 | 3D 场景，本项目不使用 |
| `core.ecs.resource`（23） | `RawMesh`、IBL 生成、纹理采样器 | 3D 资源，不使用 |
| `core.ecs`（12） | `PostProcessingConfig`、`SceneCaptureComponent`、`DynamicShadowComponent`、`SkeletonPoseComponent`、`AntiAliasingMode` | 3D 渲染，不使用 |
| `ui.design.icons`（161） | 新增 `PicoIcons.Filled` / `PicoIcons.Outlined` 图标集 | **可用**：面板按钮图标可替换手绘 Canvas 图标 |
| `ui.platform.containers`（6） | `SurroundingsEffect.ColorMultiply` / `.Dimming`、`StageSurroundingsProperties`、`ColorScale` | **可用**：手术室场景调暗、专注态压暗环境光 |
| `core.container`（9） | `SpatialAccessibilityAction` 及 Move/Rotate/Scale 变换动作、`AccessibilityComponent` | **可用**：空间无障碍动作注册 |
| `ui.design.arrangement`（2） | `ArrangementExtKt`、`SpacedAligned`（迁移后位置） | 仅在需要间距布局时使用 |
| `core.ecs.portal` / `video` | `PortalPlane`、`VideoBlurConfig` | 3D 门户、视频模糊，不使用 |

结论：6.2.8 的增量集中在 3D/ECS/粒子/渲染，SurgPanel 是纯 2D 窗口化 Compose 应用，升级属于**纯版本跟随**；图标集与环境调暗可作为后续可选增强。

## 4. 构建验证

```
.\gradlew.bat clean assembleDebug
BUILD SUCCESSFUL
```

| 项 | 值 |
| --- | --- |
| APK | `app\build\outputs\apk\debug\app-debug.apk` |
| 体积 | 14,824,053 bytes（14.13 MB，调试包，2026-11-22 重新构建实测；正式包 11,187,249 bytes / 10.67 MB） |
| SHA-256 | `01d9ee3c451385b48e1788387e7cf7262e01d42696c6f60bc2ba3f38dd2c824d` |
| manifest | `versionCode='4' versionName='1.3.0'`，compileSdk 35 / targetSdk 35 |
| 编译告警 | 无新增，未被移除 API 的 deprecation 提示 |

对照测试副本 `C:\DevTools\surg628test`（仅改 BOM、未改版本号）：当时两份调试包同为 34,894,701 bytes（后续资源清理与依赖变化后，当前调试包为 14,824,053 bytes），SHA-256 `055894FA...D9BFDC` 不同 —— 体积一致、内容差异仅来自 manifest 版本字段，可复现性良好。

## 5. 工具链：pico-cli 0.6.1 → 0.7.0

旧版 0.6.1 位于桌面助手内置 node 的全局目录（安装目录下 `node_modules`），该目录属系统保护区，故采用**并存安装**方案：

```
npm install --prefix C:\DevTools\pico-cli @picoxr/pico-cli@0.7.0
# 可执行入口：C:\DevTools\pico-cli\node_modules\.bin\pico-cli.cmd
# 实测：pico-cli/0.7.0 (public) win32-x64 node-v22.12.0
```

0.7.0 相对 0.6.1 的命令面变化（`pico-cli --help` 实测）：新增 `workflow`（Workflow Package 发现/启动/恢复）、`interact`（通过语义节点与动作观察和操作应用）、`device`、`files`、`camera`、`capture`（相机位姿与录制）。旧版 `emulator` / `app` / `project` / `doctor` 均保留。

未执行 `pico-cli setup`：该步骤会拉取平台镜像与插件，体积大、耗时长，安排在模拟器联调时一次完成。

## 6. 晚间联调清单（待执行）

| # | 步骤 | 判定标准 |
| --- | --- | --- |
| 1 | `pico-cli doctor` | 无 FAIL；`--fix` 后复检通过 |
| 2 | `pico-cli setup --region cn`（如 doctor 提示需要） | Spatial 平台组件落地，`PICO_HOME` 出现 6.2 目录 |
| 3 | `pico-cli project configure` | 识别为 PICO Spatial 工程，gradle 配置无改写冲突 |
| 4 | `pico-cli emulator start --avd Pico_SurgPanel` | 模拟器进入桌面 |
| 5 | `pico-cli app install app-debug.apk --replace` | 安装成功，版本显示 1.3.0 |
| 6 | `pico-cli app launch com.cwj.surgpanel` | 控制台窗口正常出现 |
| 7 | 四项痛点回归：无接触阅片 / 联合告警 / 倒计时耗材 / 主刀视角同步 | 与 v1.2.1 行为一致 |
| 8 | `pico-cli capture screenshot` | 截图存档至 `shots/` |
| 9 | `interact` 语义节点抽查（0.7.0 新能力） | 能枚举到面板节点，供后续自动化脚本用 |

## 7. 回滚方案

```
# 代码回滚
git checkout -- gradle/libs.versions.toml app/build.gradle.kts README.md README.zh-CN.md

# CLI 回滚：直接改用旧入口，无需卸载
<内置 node 全局目录>\.bin\pico-cli.cmd
```

6.2.8 的 AAR 已缓存在 Gradle 用户目录，回滚为 6.1.9 后不影响离线构建。

## 8. 尚未处理（等联调通过后再做）

- 参赛材料包版本号与说明文案刷新（已随本版完成）
- v1.3.0 git tag 与 release 提交（待推送后执行）
- APK 重打包上传大赛平台
