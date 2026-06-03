# 轻量自管弹幕引擎设计（替代 akdanmaku）

- 日期：2026-06-03
- 状态：已批准设计，待写实现计划
- 背景：2GB 低端 TV 盒子开弹幕滚动卡顿、卡顿尖峰时整体卡死闪退。根因是 akdanmaku 1.0.3 在**主线程** `onDraw` 渲染 + 50MB ARGB 位图池 + 透明度走逐条 `saveLayerAlpha` 离屏风暴，叠加 1.0.3 的 ActionThread 泄漏（项目靠反射 hack 收尾）。v1.4.2 已临时压住（透明度移到 View.alpha、低端机降密度/降缓存池），本设计目标是**彻底替换** akdanmaku，从基底上消除主线程渲染与位图池。

## 目标与硬指标

替换 akdanmaku 的自研轻量弹幕库，须同时满足：

1. **渲染不上主线程**——绘制在专用渲染线程完成，焦点动画/列表滚动与弹幕互不阻塞。
2. **绝不使用离屏层**——不调 `saveLayer*`；整体不透明度折入每条 paint 的 alpha 通道（单 primitive 带 alpha，无离屏 FBO）。
3. **无内存泄漏**——干净的线程 + Surface 生命周期，不依赖任何反射 hack。
4. **低 CPU**——硬件 Canvas `drawText` + 空闲门控 + 零每帧分配。
5. **低内存**——无 ARGB 位图池；活跃弹幕集被「轨道数」封顶。

零行为回归：现有全部配置项与显示区域行为保持一致。

## 决策（已确认）

- **替换范围**：一锅端，彻底移除 `com.kuaishou:akdanmaku:1.0.3` 及其周边 hack。不保留 fallback。
- **渲染基底**：`TextureView` + 后台渲染线程 + 硬件 Canvas（`Surface(surfaceTexture).lockHardwareCanvas()`，API 23 = minSdk 23）。
  - 选 TextureView 而非 SurfaceView：它是视图层级内的普通视图，Compose `AndroidView` 的 z-order 原样生效（drop-in 替换现 `DanmakuView`），无需 `setZOrderMediaOverlay` 手动理顺与播放器 surface 类型的堆叠坑。
  - 选硬件 Canvas 而非裸 OpenGL：`drawText` 由系统做 GPU 字形缓存，省去自建 glyph atlas 的高风险重造文本渲染；仍然达成「off-main-thread」这一真实诉求。

## 现状事实（设计依据）

- **数据层**：6 个源（B站/腾讯/爱奇艺/优酷/芒果/乐视）全部**分片**拉取（60~360s/片）。`PlayerScreen` 每 4s 增量把新片 merge 进 `danmakuCommentsByKey[key]`，按 `timeMs*31 + content.hashCode()` 去重；同一时刻只渲染**单个源**。整批 (`getComments`) 是 fallback。两者最终都收敛成一个**增量增长的 `List<DanmakuComment>`**。
- **DanmakuComment**：`timeMs: Long`、`mode: Int`（1 滚动 / 4 底部 / 5 顶部）、`color: Long`（取低 24 位 RGB）、`content: String`。
- **DanmakuRenderSettings**：`opacity` 0.4~1.0、`speed` 0.5~2.0（`durationMs = 8000/speed`）、`fontScale` 0.7~1.4、`densityPct` 25/50/75/100（数据层抽样）、`antiOverlap`。持久化键在 `AppDataService`（`KEY_DANMAKU_*`）。
- **显示区域**：`off / third(0.34) / half(0.5) / full(1.0)`，在 `PlayerScreen` 用 `fillMaxHeight(fraction)` 给 overlay 顶对齐定高。
- **低端机策略**：`AppDataService.isLowRamDeviceSync()` 为真时密度默认 50。**保留**。
- **akdanmaku 的债**（移除目标）：主线程 `onDraw`、50MB `CACHE_POOL_MAX_MEMORY_SIZE` 位图池、`saveLayerAlpha` 风暴、`DanmakuPlayerReleaser` 反射 hack、1.0.3 ActionThread 泄漏。

## 架构

新包 `org.moontechlab.selenetv.ui.danmaku/`，四层，核心层零 Android-View 依赖以便 JVM 单测。

### 单元与职责

| 单元 | 职责 | 依赖 |
|---|---|---|
| `DanmakuItem` | 引擎输入：`id: Long`、`timeMs: Long`（视频时间轴出现点）、`text: String`、`mode`、`colorRgb: Int`、懒测 `width: Float`（首次 spawn 时由渲染线程 `Paint.measureText` 测得并缓存） | 纯 Kotlin |
| `DanmakuEngine` | **可单测核心**：按 timeMs 排序的列表 + 时间游标 + 活跃集 + 分轨布局 + 位置数学。`advance(nowMs)` 改活跃集；`draw(canvas)` 纯绘制。状态只在渲染线程触碰 | `android.graphics.{Canvas,Paint}` |
| `DanmakuRenderThread` | HandlerThread + 自带 Choreographer 的 vsync 循环；持 `Surface`，每帧 `lockHardwareCanvas` → clear → `advance+draw` → `unlockCanvasAndPost`。空闲门控 | Android |
| `DanmakuTextureView` | 继承 `TextureView` 实现 `SurfaceTextureListener`：available→建 Surface 起线程，destroyed→停线程释放 Surface 返回 true，sizeChanged→更新引擎尺寸 | Android |
| `DanmakuOverlay`（重写） | Compose 包装，**签名与现版完全一致**（`comments, positionMs, isPlaying, speed, visible, settings, previewMode, datasetKey, modifier`）。LaunchedEffect 把入参 `handler.post` 到渲染线程 | Compose |

### 渲染管线（off-main-thread，绝不 saveLayer）

1. `onSurfaceTextureAvailable` → `Surface(surfaceTexture)` + 启动 `DanmakuRenderThread`。
2. 渲染线程每个 vsync 回调：
   - `surface.lockHardwareCanvas()`（GPU 加速）
   - `canvas.drawColor(0, PorterDuff.Mode.CLEAR)` 整屏清空（TextureView 默认透明）
   - 逐条活跃弹幕 `canvas.drawText(...)`，**`paint.color` 折入 opacity 的 alpha**（单 primitive 带 alpha = 零离屏层）。复用同一个 `Paint` 对象，仅按需改 color/textSize
   - `surface.unlockCanvasAndPost(canvas)`
3. glyph 由系统 GPU 字形缓存 → 零位图池、零每帧分配。

### 线程模型（无锁）

引擎状态**只在渲染线程触碰**。Compose 侧所有变更（喂数据、改配置、改时钟、seek、暂停）一律 `handler.post{}` 到渲染线程串行执行 → 天然无锁、无竞态。

### 数据消费（统一吃分片与整批）

引擎持**整条按 `timeMs` 排序的列表 + 一个时间游标**。`setItems(list)` 增量 merge 后重排，二分把游标定到 `timeMs >= 当前钟` 的第一项——早于当前钟的新插入项视为**已过期，永不补冒**。每帧把 `(上次钟, nowMs]` 区间内的项 spawn 进轨道。分片渐进增长、整批一次到位，对引擎完全一致；`PlayerScreen` 的去重/增量逻辑原样不动。

### 时钟与同步（沿用现策略）

- 引擎自持单调钟，按帧 delta × `playbackSpeed` 推进。
- 每 ~2s 与宿主 `positionMs` 比对，`|drift| > 1200ms` 才 `seek` 重定游标（含活跃集清空与游标二分重置）。
- `isPlaying=false` 冻结钟；`previewMode=true` 下自由飞（设置弹窗实时预览速度/密度）。

### 分轨布局

- 轨数 = `floor(viewHeight / laneHeight)`，`laneHeight = textSizePx × lineHeightFactor`。显示区域由 TextureView 视图高度（`fillMaxHeight(fraction)`）决定，引擎只认 view 高度。
- **滚动（mode 1）**：水平泳道。`speed = (viewWidth + width) / rollingDurationMs`；`x = viewWidth - speed × (now - spawnTime)`；生命周期 = 滚出左边。新弹幕进轨：`antiOverlap=true` 时只进「上一条已完全离开入场区」的轨，无可用轨则**丢弃该条**；`antiOverlap=false` 选最空轨。
- **顶部（mode 5）**：水平居中，从上往下堆叠空闲轨，停留固定 `displayDurationMs`（默认 ~4.5s）。
- **底部（mode 4）**：水平居中，从下往上堆叠，停留固定 `displayDurationMs`。

### 配置映射（全量对齐，零回归）

| 设置 | 映射 |
|---|---|
| `opacity` | 折入每条 paint 的 alpha 通道（不走 View.alpha、不走 saveLayer） |
| `speed` | `rollingDurationMs = 8000 / speed` |
| `fontScale` | `textSizePx = base × fontScale`（变更触发轨数重算） |
| `densityPct` | **沿用 `DanmakuOverlay` 现有数据层抽样**，引擎不感知 |
| `antiOverlap` | true=分轨拒绝重叠（放不下丢弃）；false=选最空轨 |
| 显示区域 | **沿用现 `fillMaxHeight(fraction)`** 给 TextureView 定高，引擎按 view 高算轨数 |
| `mode` 1/4/5 | 滚动 / 顶部居中停留 / 底部居中停留 |
| `color` | 低 24 位 RGB `or` opacity alpha → ARGB |

### 空闲门控（低 CPU）

暂停且非预览、且无顶/底停留弹幕处于变化中时，停帧循环；`seek`/`resume`/配置变更只投递一次性重绘。避免静止时空跑 vsync。

## 移除清单（一锅端）

- `app/build.gradle.kts`：删 `implementation("com.kuaishou:akdanmaku:1.0.3")`。
- 删 `screen/player/DanmakuPlayerReleaser.kt`（反射 hack）。
- `SeleneApplication.kt`：删 `CACHE_POOL_MAX_MEMORY_SIZE` 设置块（含 import）。
- `screen/player/DanmakuOverlay.kt`：内部 akdanmaku 调用全替换为新引擎；对外签名不变。
- `app/proguard-rules.pro`：移除 akdanmaku 相关保留规则（若有）。
- 保留：`AppDataService` 低端机 `lowRam → density 50` 逻辑。

## 测试策略

- **核心引擎 JVM 单测**（akdanmaku 时代做不到）：
  - 游标 spawn/retire 时间窗口正确性（含增量 merge、seek 前后跳）。
  - 分轨分配与 `antiOverlap` 丢弃逻辑。
  - 滚动位置数学（speed/duration 换算、进出场时机）。
  - `fontScale`/`speed`/`opacity`/color 映射换算。
  - 早于当前钟的新插入项不补冒。
- **回归测试（hunt 规矩）**：针对原「增量重喂导致 OOM/ANR」与「主线程 saveLayer 风暴」，以引擎层不变量断言（活跃集被轨数封顶、零每帧重排全量数据）守护。
- **设备验证**：模拟器（4GB，`-gpu host`）冒烟无回归；**真机 2GB 盒子实测**为最终验收口径（卡顿/卡死消除、长时间观看稳定）。

## 风险与缓解

- **TextureView + 后台 lockHardwareCanvas 的生产者线程一致性**：仅渲染线程作为 SurfaceTexture 生产者，标准模式，无竞态。
- **首帧 SurfaceTexture 未就绪**：在 `onSurfaceTextureAvailable` 之后才起线程；尺寸变更走 `onSurfaceTextureSizeChanged`。
- **2GB 真机不可在本机模拟器复现卡顿**：模拟器只证无功能回归；卡顿/内存收益须真机验收（与 v1.4.2 同样口径）。
- **文本测量线程**：`Paint.measureText` 任意线程可用，在渲染线程首次 spawn 时测并缓存，避免主线程 `TextMeasureProvider` 耦合。
