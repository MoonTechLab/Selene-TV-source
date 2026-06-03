# Lightweight Danmaku Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace akdanmaku with a self-managed lightweight danmaku library that renders off the main thread on a TextureView via hardware Canvas, never uses an offscreen layer, has no leaks, and keeps CPU/memory low.

**Architecture:** A pure-Kotlin `DanmakuEngine` (cursor over a time-sorted comment list → active set with computed positions, track allocation, no `android.graphics` deps) drives drawing. A `DanmakuRenderThread` (HandlerThread + its own Choreographer) locks a hardware Canvas from `Surface(surfaceTexture).lockHardwareCanvas()` each vsync and paints the active set via `DanmakuRenderer`. A `DanmakuTextureView` owns the Surface/thread lifecycle; the rewritten `DanmakuOverlay` Compose wrapper keeps the exact same public signature and posts all mutations onto the render thread (lock-free).

**Tech Stack:** Kotlin, Jetpack Compose for TV (`AndroidView`), `android.view.TextureView`, `android.view.Surface.lockHardwareCanvas` (API 23 = minSdk), `android.view.Choreographer`, JUnit (JVM unit tests for the engine core).

**Reference spec:** `docs/superpowers/specs/2026-06-03-lightweight-danmaku-engine-design.md`

---

## File Structure

New package `org.moontechlab.selenetv.ui.danmaku/` (engine + render). Mapping + Compose wrapper stay in `screen/player/`.

| File | Responsibility | Android deps? |
|---|---|---|
| `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuModels.kt` | `DanmakuMode`, `DanmakuItem`, `TextMeasurer`, `DanmakuEngineConfig`, `ActiveDanmaku`, `argbColor()` | No (pure Kotlin) |
| `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngine.kt` | Cursor/spawn/retire, track allocation, position math, active set | No (pure Kotlin) |
| `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/PaintTextMeasurer.kt` | `TextMeasurer` impl via `android.graphics.Paint` | Yes |
| `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderer.kt` | Clear + `drawText` the active set onto a Canvas | Yes |
| `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderThread.kt` | vsync loop, clock, drift-correct, lock/draw/post, idle gate | Yes |
| `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuTextureView.kt` | Surface + render-thread lifecycle, submit* pass-throughs | Yes |
| `app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuMapping.kt` | `DanmakuComment.toEngineItem()`, `buildEngineConfig()` | No (pure Kotlin) |
| `app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuOverlay.kt` | Rewritten Compose wrapper (same signature) | Yes (Compose) |
| `app/src/test/java/org/moontechlab/selenetv/ui/danmaku/*Test.kt` | JVM unit tests for engine + helpers | No |

Removals: `screen/player/DanmakuPlayerReleaser.kt`, akdanmaku dep in `app/build.gradle.kts`, `CACHE_POOL_MAX_MEMORY_SIZE` block in `SeleneApplication.kt`, any akdanmaku ProGuard rule.

---

## Task 1: Core value types + color helper

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuModels.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuColorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuColorTest.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuColorTest {
    @Test fun opaqueRed() {
        assertEquals(0xFFFF0000.toInt(), argbColor(0xFF0000, 1f))
    }

    @Test fun halfAlphaWhite() {
        val c = argbColor(0xFFFFFF, 0.5f)
        assertEquals(127, (c ushr 24) and 0xFF)
        assertEquals(0xFFFFFF, c and 0xFFFFFF)
    }

    @Test fun stripsHighBitsOfColor() {
        // 入参可能是 ARGB（如优酷 0xFFFFFFFF.toInt()）→ 只取低 24 位
        assertEquals(0xFFFFFFFF.toInt(), argbColor(0xFFFFFFFF.toInt(), 1f))
    }

    @Test fun opacityClamped() {
        assertEquals(255, (argbColor(0, 2f) ushr 24) and 0xFF)
        assertEquals(0, (argbColor(0, -1f) ushr 24) and 0xFF)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.ui.danmaku.DanmakuColorTest"`
Expected: FAIL — `argbColor` / package unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuModels.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

/** 弹幕模式（对齐 DanmakuComment.mode：1 滚动 / 5 顶 / 4 底）。 */
enum class DanmakuMode { SCROLL, TOP, BOTTOM }

/** 引擎输入：一条弹幕在视频时间轴上的出现点与外观。colorRgb 取低 24 位。 */
data class DanmakuItem(
    val id: Long,
    val timeMs: Long,
    val text: String,
    val mode: DanmakuMode,
    val colorRgb: Int,
)

/** 文本宽度测量抽象：设备上用 Paint，单测用假实现，使引擎核心零 android.graphics 依赖。 */
interface TextMeasurer {
    fun measure(text: String, textSizePx: Float): Float
}

/**
 * 引擎配置（外观/行为）。opacity/visible 不影响布局，仅供 DanmakuRenderer 绘制时使用。
 */
data class DanmakuEngineConfig(
    val rollingDurationMs: Long = 8000L,
    val topBottomDurationMs: Long = 4500L,
    val textSizePx: Float = 42f,
    val lineHeightFactor: Float = 1.4f,
    val antiOverlap: Boolean = false,
    val opacity: Float = 0.85f,
    val visible: Boolean = true,
)

/** 活跃弹幕（对象池复用，避免每帧分配）。x/y 每帧由 advance 重算。 */
class ActiveDanmaku {
    var inUse = false
    var id = 0L
    var text = ""
    var colorRgb = 0
    var mode = DanmakuMode.SCROLL
    var spawnTimeMs = 0L
    var width = 0f
    var lane = 0
    var x = 0f
    var y = 0f
}

/** 低 24 位 RGB 折入不透明度 → ARGB（绝不走 saveLayer：单 drawText 自带 alpha）。 */
fun argbColor(colorRgb: Int, opacity: Float): Int {
    val a = (opacity.coerceIn(0f, 1f) * 255f).toInt()
    return (a shl 24) or (colorRgb and 0xFFFFFF)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.ui.danmaku.DanmakuColorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuModels.kt \
        app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuColorTest.kt
git commit -m "feat(danmaku): 引擎核心值类型 + ARGB 色彩折叠

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Engine spawn / retire / seek (time cursor)

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngine.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngineTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngineTest.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** 固定宽度的假测量：宽度 = 字数 × 字号 × 0.5，与设备无关、可断言。 */
class FakeMeasurer : TextMeasurer {
    override fun measure(text: String, textSizePx: Float): Float = text.length * textSizePx * 0.5f
}

private fun scroll(id: Long, t: Long, text: String = "abc") =
    DanmakuItem(id, t, text, DanmakuMode.SCROLL, 0xFFFFFF)

class DanmakuEngineTest {

    private fun engine(): DanmakuEngine = DanmakuEngine(FakeMeasurer()).apply {
        setViewport(1920, 1080)
        setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f))
    }

    @Test fun spawnsOnlyDueItems() {
        val e = engine()
        e.setItems(listOf(scroll(1, 1000), scroll(2, 2000), scroll(3, 3000)))
        e.advance(1500)
        assertEquals(listOf(1L), e.active.map { it.id })
        e.advance(2500)
        assertEquals(setOf(1L, 2L), e.active.map { it.id }.toSet())
    }

    @Test fun retiresAfterRollingDuration() {
        val e = engine()
        e.setItems(listOf(scroll(1, 0)))
        e.advance(0)
        assertTrue(e.active.any { it.id == 1L })
        e.advance(8001)  // elapsed > rollingDurationMs → 滚出左边界
        assertFalse(e.active.any { it.id == 1L })
    }

    @Test fun seekClearsActiveAndRepositionsCursor() {
        val e = engine()
        e.setItems((1..10).map { scroll(it.toLong(), it * 1000L) })
        e.advance(2500)
        assertTrue(e.active.isNotEmpty())
        e.seekTo(5000)
        assertTrue(e.active.isEmpty())
        e.advance(5000)            // 仅 t<=5000 的下一条（id=5）入场
        assertEquals(listOf(5L), e.active.map { it.id })
    }

    @Test fun seekBackwardDoesNotReplayFromZero() {
        val e = engine()
        e.setItems((1..10).map { scroll(it.toLong(), it * 1000L) })
        e.advance(9000)
        e.seekTo(3000)
        e.advance(3000)
        assertEquals(listOf(3L), e.active.map { it.id })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.ui.danmaku.DanmakuEngineTest"`
Expected: FAIL — `DanmakuEngine` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngine.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

/**
 * 弹幕布局引擎（纯 Kotlin，零 android.graphics 依赖 → JVM 可单测）。
 *
 * 持一条按 timeMs 排序的列表 + 时间游标：advance(nowMs) 把落入 (上次, now] 的项 spawn 进轨道、
 * retire 过期项、重算每条位置；draw 由外部 DanmakuRenderer 读取 [active] 完成。所有状态只应在
 * 渲染线程触碰（调用方保证），故内部不加锁。
 */
class DanmakuEngine(private val measurer: TextMeasurer) {

    var config = DanmakuEngineConfig(); private set

    private var items: List<DanmakuItem> = emptyList()
    private var cursor = 0
    private var clockMs = 0L
    private var viewW = 0
    private var viewH = 0

    private val pool = ArrayList<ActiveDanmaku>()
    private val activeList = ArrayList<ActiveDanmaku>()

    /** 只读活跃集（渲染绘制 / 单测断言）。 */
    val active: List<ActiveDanmaku> get() = activeList
    val clock: Long get() = clockMs

    fun setViewport(w: Int, h: Int) { viewW = w; viewH = h }
    fun setConfig(c: DanmakuEngineConfig) { config = c }

    /** 增量 merge 后按 timeMs 排序，游标重定位到当前钟（早于当前钟的新插入项不补冒）。 */
    fun setItems(list: List<DanmakuItem>) {
        items = list.sortedBy { it.timeMs }
        cursor = firstIndexAtOrAfter(clockMs)
    }

    /** 跳转：清空活跃集，游标二分到目标时间。 */
    fun seekTo(ms: Long) {
        clockMs = ms
        recycleAll()
        cursor = firstIndexAtOrAfter(ms)
    }

    /** 推进到绝对时间 nowMs：spawn 到期项、retire 过期项、重算位置。 */
    fun advance(nowMs: Long) {
        clockMs = nowMs
        while (cursor < items.size && items[cursor].timeMs <= nowMs) {
            spawn(items[cursor]); cursor++
        }
        val laneH = laneHeight()
        var i = 0
        while (i < activeList.size) {
            val d = activeList[i]
            val expired = when (d.mode) {
                DanmakuMode.SCROLL -> {
                    val speed = (viewW + d.width) / config.rollingDurationMs.toFloat()
                    d.x = viewW - speed * (nowMs - d.spawnTimeMs)
                    d.y = d.lane * laneH + laneH
                    d.x + d.width < 0f
                }
                DanmakuMode.TOP -> {
                    d.x = (viewW - d.width) / 2f
                    d.y = d.lane * laneH + laneH
                    nowMs - d.spawnTimeMs > config.topBottomDurationMs
                }
                DanmakuMode.BOTTOM -> {
                    d.x = (viewW - d.width) / 2f
                    d.y = viewH - d.lane * laneH
                    nowMs - d.spawnTimeMs > config.topBottomDurationMs
                }
            }
            if (expired) { recycle(d); activeList.removeAt(i) } else i++
        }
    }

    // ---- internals ----

    private fun laneHeight(): Float = config.textSizePx * config.lineHeightFactor

    private fun laneCount(): Int {
        val lh = laneHeight()
        if (lh <= 0f) return 1
        return maxOf(1, (viewH / lh).toInt())
    }

    private fun firstIndexAtOrAfter(ms: Long): Int {
        var lo = 0; var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].timeMs < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun spawn(item: DanmakuItem) {
        val width = measurer.measure(item.text, config.textSizePx)
        val lane = allocLane(item.mode, width)
        if (lane < 0) return  // 防重叠放不下 / 无空轨 → 丢弃
        val d = obtain()
        d.id = item.id; d.text = item.text; d.colorRgb = item.colorRgb
        d.mode = item.mode; d.spawnTimeMs = clockMs; d.width = width; d.lane = lane
        activeList.add(d)
    }

    private fun allocLane(mode: DanmakuMode, width: Float): Int = when (mode) {
        DanmakuMode.SCROLL -> allocScrollLane(laneCount(), width)
        DanmakuMode.TOP -> allocStaticLane(laneCount(), DanmakuMode.TOP)
        DanmakuMode.BOTTOM -> allocStaticLane(laneCount(), DanmakuMode.BOTTOM)
    }

    private fun allocScrollLane(count: Int, width: Float): Int {
        var bestLane = -1
        var bestGap = Float.NEGATIVE_INFINITY
        for (lane in 0 until count) {
            val last = lastScrollInLane(lane) ?: return lane  // 空轨直接用
            val lastSpeed = (viewW + last.width) / config.rollingDurationMs.toFloat()
            val lastRight = viewW - lastSpeed * (clockMs - last.spawnTimeMs) + last.width
            if (config.antiOverlap) {
                if (lastRight <= viewW) {                 // 前一条已完全入场才允许
                    val gap = viewW - lastRight
                    if (gap > bestGap) { bestGap = gap; bestLane = lane }
                }
            } else {
                val gap = viewW - lastRight                // 越大越空
                if (gap > bestGap) { bestGap = gap; bestLane = lane }
            }
        }
        return bestLane
    }

    private fun lastScrollInLane(lane: Int): ActiveDanmaku? {
        var found: ActiveDanmaku? = null
        for (d in activeList) {
            if (d.mode == DanmakuMode.SCROLL && d.lane == lane &&
                (found == null || d.spawnTimeMs > found.spawnTimeMs)
            ) found = d
        }
        return found
    }

    private fun allocStaticLane(count: Int, mode: DanmakuMode): Int {
        for (lane in 0 until count) {
            var occupied = false
            for (d in activeList) if (d.mode == mode && d.lane == lane) { occupied = true; break }
            if (!occupied) return lane
        }
        return -1
    }

    private fun obtain(): ActiveDanmaku {
        for (d in pool) if (!d.inUse) { d.inUse = true; return d }
        return ActiveDanmaku().also { it.inUse = true; pool.add(it) }
    }
    private fun recycle(d: ActiveDanmaku) { d.inUse = false }
    private fun recycleAll() { for (d in activeList) d.inUse = false; activeList.clear() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.ui.danmaku.DanmakuEngineTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngine.kt \
        app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngineTest.kt
git commit -m "feat(danmaku): 引擎时间游标 spawn/retire/seek

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Scroll lane allocation (overlap + anti-overlap)

**Files:**
- Modify: `app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngineTest.kt` (append)

(The scroll allocation code already landed in Task 2; this task adds the tests that pin its behavior.)

- [ ] **Step 1: Write the failing test**

Append to `DanmakuEngineTest.kt` (inside the class):

```kotlin
    @Test fun scrollSpreadsAcrossLanesWhenOverlapAllowed() {
        val e = DanmakuEngine(FakeMeasurer()).apply {
            setViewport(1920, 1080)
            setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f, antiOverlap = false))
        }
        // 同一时刻 3 条 → 选最空轨，应落到 3 条不同泳道
        e.setItems(listOf(scroll(1, 0), scroll(2, 0), scroll(3, 0)))
        e.advance(0)
        assertEquals(3, e.active.map { it.lane }.toSet().size)
    }

    @Test fun antiOverlapDropsWhenAllLanesBusy() {
        // 轨高 = 40*1.4 = 56；viewH 56 → 仅 1 条泳道。防重叠下同刻第二条无处可放 → 丢弃。
        val e = DanmakuEngine(FakeMeasurer()).apply {
            setViewport(1920, 56)
            setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f, antiOverlap = true))
        }
        e.setItems(listOf(scroll(1, 0), scroll(2, 0)))
        e.advance(0)
        assertEquals(listOf(1L), e.active.map { it.id })
    }

    @Test fun antiOverlapAdmitsAfterPreviousFullyEntered() {
        val e = DanmakuEngine(FakeMeasurer()).apply {
            setViewport(1920, 56)  // 单泳道
            setConfig(DanmakuEngineConfig(rollingDurationMs = 8000L, textSizePx = 40f, antiOverlap = true))
        }
        e.setItems(listOf(scroll(1, 0), scroll(2, 7999)))
        e.advance(0)
        e.advance(7999)  // 第一条接近滚完、右缘早已离开右边界 → 第二条可入场
        assertTrue(e.active.any { it.id == 2L })
    }
```

- [ ] **Step 2: Run test to verify it fails (then passes)**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.ui.danmaku.DanmakuEngineTest"`
Expected: these 3 new tests PASS (logic implemented in Task 2). If any fails, fix `allocScrollLane` per the assertions before committing.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngineTest.kt
git commit -m "test(danmaku): 滚动分轨与防重叠丢弃/入场断言

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Top/bottom stacking + incremental merge no-backfill

**Files:**
- Modify: `app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngineTest.kt` (append)

- [ ] **Step 1: Write the failing test**

Append to `DanmakuEngineTest.kt` (inside the class):

```kotlin
    private fun top(id: Long, t: Long) = DanmakuItem(id, t, "x", DanmakuMode.TOP, 0xFFFFFF)
    private fun bottom(id: Long, t: Long) = DanmakuItem(id, t, "x", DanmakuMode.BOTTOM, 0xFFFFFF)

    @Test fun topStacksFromTop() {
        val e = engine()
        e.setItems(listOf(top(1, 0), top(2, 0)))
        e.advance(0)
        val lanes = e.active.filter { it.mode == DanmakuMode.TOP }.map { it.lane }.toSet()
        assertEquals(setOf(0, 1), lanes)
        // lane 0 在 lane 1 上方
        val y0 = e.active.first { it.id == 1L }.y
        val y1 = e.active.first { it.id == 2L }.y
        assertTrue(y0 < y1)
    }

    @Test fun bottomGrowsUpwardFromBottom() {
        val e = engine()
        e.setItems(listOf(bottom(1, 0), bottom(2, 0)))
        e.advance(0)
        val y0 = e.active.first { it.id == 1L }.y  // lane 0 最底
        val y1 = e.active.first { it.id == 2L }.y  // lane 1 在其上方
        assertTrue(y0 > y1)
    }

    @Test fun staticExpiresAfterDuration() {
        val e = DanmakuEngine(FakeMeasurer()).apply {
            setViewport(1920, 1080)
            setConfig(DanmakuEngineConfig(topBottomDurationMs = 4500L, textSizePx = 40f))
        }
        e.setItems(listOf(top(1, 0)))
        e.advance(0); assertTrue(e.active.any { it.id == 1L })
        e.advance(4501); assertFalse(e.active.any { it.id == 1L })
    }

    @Test fun lateChunkForPastTimeIsNotBackfilled() {
        val e = engine()
        e.setItems(listOf(scroll(1, 1000), scroll(3, 4000)))
        e.advance(3000)  // 钟到 3000；id=1 已入场
        // 新分片补来一条「过去」(t=2000) 和一条「未来」(t=4000 已在列表) → 重排后游标重定位
        e.setItems(listOf(scroll(1, 1000), scroll(2, 2000), scroll(3, 4000)))
        e.advance(3000)  // 不应补冒已过去的 id=2
        assertFalse(e.active.any { it.id == 2L })
        e.advance(4000)  // 未来项正常入场
        assertTrue(e.active.any { it.id == 3L })
    }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.ui.danmaku.DanmakuEngineTest"`
Expected: all engine tests PASS (top/bottom + merge logic implemented in Task 2).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/org/moontechlab/selenetv/ui/danmaku/DanmakuEngineTest.kt
git commit -m "test(danmaku): 顶/底堆叠与分片不补冒过去项断言

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Android render side — PaintTextMeasurer + DanmakuRenderer

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/PaintTextMeasurer.kt`
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderer.kt`

(No JVM unit test — these touch `android.graphics`; verified by compile.)

- [ ] **Step 1: Write PaintTextMeasurer**

Create `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/PaintTextMeasurer.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

import android.graphics.Paint

/** 渲染线程私有：用一个 Paint 测文本宽度。仅本线程调用，无需同步。 */
class PaintTextMeasurer : TextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun measure(text: String, textSizePx: Float): Float {
        paint.textSize = textSizePx
        return paint.measureText(text)
    }
}
```

- [ ] **Step 2: Write DanmakuRenderer**

Create `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderer.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff

/**
 * 把引擎活跃集画到一块硬件 Canvas 上。每条 drawText 自带 alpha（颜色折入不透明度）——
 * 绝不调 saveLayer*。复用同一个 Paint，零每帧分配。
 */
object DanmakuRenderer {
    fun draw(canvas: Canvas, paint: Paint, engine: DanmakuEngine) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)  // 整屏透明清空
        val cfg = engine.config
        if (!cfg.visible) return
        paint.textSize = cfg.textSizePx
        val alpha = (cfg.opacity.coerceIn(0f, 1f) * 255f).toInt()
        for (d in engine.active) {
            paint.color = (alpha shl 24) or (d.colorRgb and 0xFFFFFF)
            canvas.drawText(d.text, d.x, d.y, paint)
        }
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/ui/danmaku/PaintTextMeasurer.kt \
        app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderer.kt
git commit -m "feat(danmaku): 硬件 Canvas 渲染器 + Paint 文本测量

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: DanmakuRenderThread (off-main-thread vsync loop)

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderThread.kt`

- [ ] **Step 1: Write the render thread**

Create `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderThread.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import kotlin.math.abs

/**
 * 专用渲染线程：自带 Looper + Choreographer 的 vsync 循环，从 Surface.lockHardwareCanvas()
 * 拿 GPU 加速 Canvas 绘制。所有引擎状态变更经本线程的 Handler 串行执行 → 无锁。
 *
 * 空闲门控：仅在动画态（播放 或 预览）持续投递帧；静止态绘制一帧冻结画面后停循环，
 * 由 setPlayback/requestRedraw 重新 kick。
 */
class DanmakuRenderThread(
    private val surface: Surface,
    private val engine: DanmakuEngine,
) : HandlerThread("DanmakuRender") {

    private var handler: Handler? = null
    private var choreographer: Choreographer? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())  // 细描影提升暗背景可读性
    }

    // 以下仅本线程触碰
    private var hostPositionMs = 0L
    private var isPlaying = false
    private var previewMode = false
    private var playSpeed = 1f
    private var engineClockMs = 0L
    private var lastFrameNs = 0L
    private var lastSyncNs = 0L
    private var looping = false

    private val frameCallback = Choreographer.FrameCallback { onVsync(it) }

    override fun onLooperPrepared() {
        val h = Handler(looper)
        handler = h
        h.post {
            choreographer = Choreographer.getInstance()
            kick()
        }
    }

    fun post(r: () -> Unit) { handler?.post(r) }

    fun setPlayback(positionMs: Long, playing: Boolean, speed: Float, preview: Boolean) = post {
        if (!preview && abs(positionMs - engineClockMs) > 1200) {  // 用户拖拽即时纠正
            engineClockMs = positionMs
            engine.seekTo(positionMs)
        }
        hostPositionMs = positionMs
        isPlaying = playing
        previewMode = preview
        playSpeed = speed
        kick()
    }

    fun requestRedraw() = post { kick() }

    private fun kick() {
        if (looping) return
        looping = true
        lastFrameNs = 0L
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun onVsync(frameNs: Long) {
        val animating = isPlaying || previewMode
        if (lastFrameNs != 0L && animating) {
            val dtMs = (frameNs - lastFrameNs) / 1_000_000f
            engineClockMs += (dtMs * playSpeed).toLong()
        }
        lastFrameNs = frameNs

        if (animating && isPlaying && !previewMode) {           // 周期漂移校正
            if (lastSyncNs == 0L) lastSyncNs = frameNs
            else if (frameNs - lastSyncNs > 2_000_000_000L) {
                lastSyncNs = frameNs
                if (abs(engineClockMs - hostPositionMs) > 1200) {
                    engineClockMs = hostPositionMs
                    engine.seekTo(engineClockMs)
                }
            }
        }

        engine.advance(engineClockMs)
        drawFrame()

        if (animating) choreographer?.postFrameCallback(frameCallback)
        else looping = false                                    // 静止：冻结画面已绘，停循环省 CPU
    }

    private fun drawFrame() {
        if (!surface.isValid) return
        val canvas = try { surface.lockHardwareCanvas() } catch (e: Exception) { null } ?: return
        try {
            DanmakuRenderer.draw(canvas, paint, engine)
        } finally {
            try { surface.unlockCanvasAndPost(canvas) } catch (e: Exception) { /* surface 已失效 */ }
        }
    }

    fun quitAndJoin() {
        post {
            choreographer?.removeFrameCallback(frameCallback)
            looping = false
        }
        quitSafely()
        try { join(500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuRenderThread.kt
git commit -m "feat(danmaku): 后台渲染线程（vsync + 硬件 Canvas + 空闲门控）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: DanmakuTextureView (Surface + thread lifecycle)

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuTextureView.kt`

- [ ] **Step 1: Write the view**

Create `app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuTextureView.kt`:

```kotlin
package org.moontechlab.selenetv.ui.danmaku

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/**
 * 承载弹幕的 TextureView：onAvailable 建 Surface 起渲染线程，onDestroyed 干净收尾（无反射、无泄漏）。
 * submit* 把变更交给渲染线程；onAvailable 之前的最新状态缓存下来，避免早到的提交丢失。
 */
class DanmakuTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private val engine = DanmakuEngine(PaintTextMeasurer())
    private var renderThread: DanmakuRenderThread? = null
    private var surface: Surface? = null

    private var pendingItems: List<DanmakuItem> = emptyList()
    private var pendingConfig: DanmakuEngineConfig? = null
    private var lastPlayback: PlaybackState? = null

    private data class PlaybackState(val pos: Long, val playing: Boolean, val speed: Float, val preview: Boolean)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun submitItems(list: List<DanmakuItem>) {
        pendingItems = list
        renderThread?.let { t -> t.post { engine.setItems(list) }; t.requestRedraw() }
    }

    fun submitConfig(config: DanmakuEngineConfig) {
        pendingConfig = config
        renderThread?.let { t -> t.post { engine.setConfig(config) }; t.requestRedraw() }
    }

    fun submitPlayback(pos: Long, playing: Boolean, speed: Float, preview: Boolean) {
        lastPlayback = PlaybackState(pos, playing, speed, preview)
        renderThread?.setPlayback(pos, playing, speed, preview)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val s = Surface(st)
        surface = s
        engine.setViewport(w, h)
        pendingConfig?.let { engine.setConfig(it) }
        engine.setItems(pendingItems)
        val t = DanmakuRenderThread(s, engine)
        renderThread = t
        t.start()
        lastPlayback?.let { t.setPlayback(it.pos, it.playing, it.speed, it.preview) }
        t.requestRedraw()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        renderThread?.let { t -> t.post { engine.setViewport(w, h) }; t.requestRedraw() }
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderThread?.quitAndJoin()
        renderThread = null
        surface?.release()
        surface = null
        return true
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/ui/danmaku/DanmakuTextureView.kt
git commit -m "feat(danmaku): TextureView 承载 Surface/渲染线程生命周期

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Mapping helpers (comment → item, settings → config)

**Files:**
- Create: `app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuMapping.kt`
- Test: `app/src/test/java/org/moontechlab/selenetv/screen/player/DanmakuMappingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/moontechlab/selenetv/screen/player/DanmakuMappingTest.kt`:

```kotlin
package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuRenderSettings
import org.moontechlab.selenetv.ui.danmaku.DanmakuMode

class DanmakuMappingTest {

    @Test fun mapsModeAndColor() {
        val s = DanmakuComment(timeMs = 1000, mode = DanmakuComment.MODE_SCROLL, color = 0xFF0000L, content = "hi")
        val item = s.toEngineItem()
        assertEquals(DanmakuMode.SCROLL, item.mode)
        assertEquals(0xFF0000, item.colorRgb)
        assertEquals(1000L, item.timeMs)
        assertEquals("hi", item.text)
        assertEquals(1000L * 31 + "hi".hashCode(), item.id)

        assertEquals(DanmakuMode.TOP,
            DanmakuComment(0, DanmakuComment.MODE_TOP, 0xFFFFFFL, "x").toEngineItem().mode)
        assertEquals(DanmakuMode.BOTTOM,
            DanmakuComment(0, DanmakuComment.MODE_BOTTOM, 0xFFFFFFL, "x").toEngineItem().mode)
    }

    @Test fun colorTakesLow24Bits() {
        // 优酷可能给 ARGB Long
        val s = DanmakuComment(0, DanmakuComment.MODE_SCROLL, 0xFFFFFFFFL, "x")
        assertEquals(0xFFFFFF, s.toEngineItem().colorRgb)
    }

    @Test fun buildsConfigFromSettings() {
        val settings = DanmakuRenderSettings(opacity = 0.7f, speed = 2.0f, fontScale = 1.2f, antiOverlap = true)
        val cfg = buildEngineConfig(settings, density = 2f, visible = true)
        assertEquals((DanmakuRenderSettings.BASE_DURATION_MS / 2.0f).toLong(), cfg.rollingDurationMs)
        assertEquals(24f * 2f * 1.2f, cfg.textSizePx, 0.001f)
        assertEquals(0.7f, cfg.opacity, 0.001f)
        assertEquals(true, cfg.antiOverlap)
        assertEquals(true, cfg.visible)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.DanmakuMappingTest"`
Expected: FAIL — `toEngineItem` / `buildEngineConfig` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuMapping.kt`:

```kotlin
package org.moontechlab.selenetv.screen.player

import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuRenderSettings
import org.moontechlab.selenetv.ui.danmaku.DanmakuEngineConfig
import org.moontechlab.selenetv.ui.danmaku.DanmakuItem
import org.moontechlab.selenetv.ui.danmaku.DanmakuMode

/** DanmakuComment → 引擎 DanmakuItem。color 取低 24 位；id 与原 akdanmaku 口径一致。 */
fun DanmakuComment.toEngineItem(): DanmakuItem = DanmakuItem(
    id = timeMs * 31 + content.hashCode(),
    timeMs = timeMs,
    text = content,
    mode = when (mode) {
        DanmakuComment.MODE_TOP -> DanmakuMode.TOP
        DanmakuComment.MODE_BOTTOM -> DanmakuMode.BOTTOM
        else -> DanmakuMode.SCROLL
    },
    colorRgb = (color and 0xFFFFFFL).toInt(),
)

/** 渲染设置 + 屏幕密度 → 引擎配置。densityPct 在数据层抽样，不进 config。 */
fun buildEngineConfig(settings: DanmakuRenderSettings, density: Float, visible: Boolean): DanmakuEngineConfig =
    DanmakuEngineConfig(
        rollingDurationMs = (DanmakuRenderSettings.BASE_DURATION_MS / settings.speed).toLong(),
        textSizePx = 24f * density * settings.fontScale,
        opacity = settings.opacity,
        antiOverlap = settings.antiOverlap,
        visible = visible,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "org.moontechlab.selenetv.screen.player.DanmakuMappingTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuMapping.kt \
        app/src/test/java/org/moontechlab/selenetv/screen/player/DanmakuMappingTest.kt
git commit -m "feat(danmaku): comment→item / settings→config 映射 + 单测

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: Rewrite DanmakuOverlay (Compose, same signature)

**Files:**
- Modify (full rewrite): `app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuOverlay.kt`

- [ ] **Step 1: Replace the file contents**

Overwrite `app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuOverlay.kt` with:

```kotlin
package org.moontechlab.selenetv.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.moontechlab.selenetv.model.DanmakuComment
import org.moontechlab.selenetv.service.danmaku.DanmakuRenderSettings
import org.moontechlab.selenetv.ui.danmaku.DanmakuTextureView

/**
 * 弹幕叠加层（自研轻量引擎，替代 akdanmaku）。渲染在专用线程的硬件 Canvas 上完成，绝不上主线程、
 * 绝不 saveLayer、无位图池。叠在 VideoSurface 之上、控制条之下。
 *
 * [settings] 控制外观/行为（opacity/speed/fontScale/antiOverlap，经 [buildEngineConfig] 实时下发）；
 * [densityPct] 在数据层抽样；显示区域由 [modifier] 的 fillMaxHeight 决定（沿用原逻辑）。
 * [datasetKey] 变更整块重建 view（旧渲染线程干净回收），不变则同一 view 贯穿增量加载空窗。
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: Long,
    isPlaying: Boolean,
    speed: Float,
    visible: Boolean,
    settings: DanmakuRenderSettings,
    previewMode: Boolean = false,
    datasetKey: Any = Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.key(datasetKey) {
        val context = LocalContext.current
        val density = context.resources.displayMetrics.density
        var view by remember { mutableStateOf<DanmakuTextureView?>(null) }

        AndroidView(
            factory = { ctx -> DanmakuTextureView(ctx).also { view = it } },
            modifier = modifier,
        )

        // 显示密度：数据层按稳定散列抽样（沿用原口径），再映射成引擎 items。
        val items = remember(comments, settings.densityPct) {
            val pct = settings.densityPct
            comments
                .filter { pct >= 100 || (((it.timeMs * 31 + it.content.hashCode()).toInt() and 0x7fffffff) % 100) < pct }
                .map { it.toEngineItem() }
        }

        LaunchedEffect(view, items) { view?.submitItems(items) }
        LaunchedEffect(view, settings, density, visible) {
            view?.submitConfig(buildEngineConfig(settings, density, visible))
        }
        LaunchedEffect(view, positionMs, isPlaying, speed, previewMode) {
            view?.submitPlayback(positionMs, isPlaying, speed, previewMode)
        }
    }
}
```

- [ ] **Step 2: Verify compile (akdanmaku still present — that's fine here)**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (`DanmakuPlayerReleaser.kt` still references akdanmaku but is now unused; removed in Task 10.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuOverlay.kt
git commit -m "feat(danmaku): DanmakuOverlay 切换到自研引擎（签名不变）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Remove akdanmaku (dependency + hacks)

**Files:**
- Modify: `app/build.gradle.kts:125`
- Delete: `app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuPlayerReleaser.kt`
- Modify: `app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt:44-49`
- Modify (if rule exists): `app/proguard-rules.pro`

- [ ] **Step 1: Remove the dependency**

In `app/build.gradle.kts`, delete these two lines (124-125):

```kotlin
    // 弹幕渲染引擎（开源，OpenGL，纯 Kotlin 无 .so）
    implementation("com.kuaishou:akdanmaku:1.0.3")
```

- [ ] **Step 2: Delete the reflection releaser**

```bash
git rm app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuPlayerReleaser.kt
```

- [ ] **Step 3: Remove the cache-pool block in SeleneApplication**

In `app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt`, delete the block (lines 44-49):

```kotlin
        // 低端机（约 2GB）：akdanmaku 弹幕位图缓存池默认上限 50MB，在小堆设备上叠加 ExoPlayer/Compose/Coil
        // 易致内存压力与 GC 抖动 → 降到 10MB（够覆盖在屏弹幕工作集；未命中走兜底直绘，开销可控）。
        // CacheManager 在构造（进播放页创建 DanmakuPlayer 时）才读此静态值，故在此处启动期设置即可生效。
        if (AppDataService.isLowRamDeviceSync()) {
            com.kuaishou.akdanmaku.DanmakuConfig.CACHE_POOL_MAX_MEMORY_SIZE = 10 * 1024 * 1024
        }
```

(Keep the surrounding `AppDataService.init` / `LocalStore.init` and `Log.d` lines. The low-RAM density default in `AppDataService` stays untouched.)

- [ ] **Step 4: Remove any akdanmaku ProGuard rule**

Run: `grep -n -i "kuaishou\|akdanmaku" app/proguard-rules.pro || echo "no akdanmaku rule"`

If a rule is found, delete those lines. If `no akdanmaku rule`, skip.

- [ ] **Step 5: Verify no dangling references**

Run: `grep -rn "com.kuaishou\|akdanmaku\|DanmakuPlayerReleaser" app/src/main || echo "clean"`
Expected: `clean`.

- [ ] **Step 6: Full build + unit tests**

Run: `./gradlew clean assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all danmaku unit tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/org/moontechlab/selenetv/SeleneApplication.kt app/proguard-rules.pro
git rm --cached app/src/main/java/org/moontechlab/selenetv/screen/player/DanmakuPlayerReleaser.kt 2>/dev/null || true
git commit -m "chore(danmaku): 彻底移除 akdanmaku 依赖与反射 hack

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Install + emulator smoke test

**Files:** none (validation only)

- [ ] **Step 1: Confirm emulator running**

Run: `~/Library/Android/sdk/platform-tools/adb devices`
Expected: `emulator-5554   device`. If absent, start AVD `Selene_TV` with `-gpu host` (see memory `emulator-gpu-host`).

- [ ] **Step 2: Install**

Run: `./gradlew installDebug`
Expected: BUILD SUCCESSFUL, `Installed on 1 device`.

- [ ] **Step 3: Launch + open a video with danmaku**

Manually (or via the E2E adb routine in memory `tv-emulator-e2e`): launch app, play an episode that has danmaku, confirm:
- Danmaku scroll across smoothly, correct opacity/font/speed.
- Toggle display area (⅓ / half / full) → vertical extent changes.
- Open settings dialog (previewMode) while paused → danmaku keep flying.
- Pause → danmaku freeze in place (no disappear), resume → continue.
- Switch danmaku source → old danmaku cleared, new stream renders.

- [ ] **Step 4: Check logs for clean teardown (no thread leak)**

Run: `~/Library/Android/sdk/platform-tools/adb logcat -d | grep -i "DanmakuRender\|Exception" | tail -40`
Expected: no repeated `DanmakuRender` thread accumulation, no uncaught exceptions from the danmaku package.

- [ ] **Step 5: Final note**

Emulator (4GB) proves no functional regression only. The jank/freeze fix on a real **2GB** box is the acceptance criterion (same caveat as v1.4.2) — flag for real-device validation before release/version bump.

---

## Self-Review

**Spec coverage:**
- 渲染不上主线程 → Task 6 (render thread). ✓
- 绝不 saveLayer / alpha 折入颜色 → Task 1 `argbColor` + Task 5 `DanmakuRenderer`. ✓
- 无泄漏（无反射）→ Task 7 `onSurfaceTextureDestroyed` + Task 10 删 releaser. ✓
- 低 CPU（空闲门控 + 零分配）→ Task 6 idle gate + Task 2 object pool. ✓
- 低内存（无位图池）→ Task 10 删 CACHE_POOL；无 Bitmap 全程. ✓
- 分片/整批统一消费 → Task 2 cursor + Task 4 no-backfill test. ✓
- 时钟同步/漂移校正/暂停/预览 → Task 6. ✓
- 配置映射全量（opacity/speed/font/density/overlap/area/mode/color）→ Task 8 + Task 9 (density 抽样) + Task 1 (color) + Task 2 (mode/overlap). ✓
- 显示区域沿用 fillMaxHeight → Task 9 modifier 透传. ✓
- 一锅端移除清单 → Task 10. ✓
- 核心 JVM 单测 → Tasks 1-4, 8. ✓

**Type consistency:** `DanmakuEngine` API (`setViewport/setConfig/setItems/seekTo/advance/active/clock`) used consistently across Tasks 2/6/7. `DanmakuEngineConfig` fields (`rollingDurationMs/topBottomDurationMs/textSizePx/lineHeightFactor/antiOverlap/opacity/visible`) consistent across Tasks 1/5/6/8. `DanmakuTextureView.submit{Items,Config,Playback}` consistent Tasks 7/9. `toEngineItem/buildEngineConfig` consistent Tasks 8/9. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code. ✓

**Verified against source:** `DanmakuComment(timeMs: Long, mode: Int, color: Long, content: String)` with `MODE_SCROLL=1 / MODE_BOTTOM=4 / MODE_TOP=5` (confirmed in `model/DanmakuComment.kt`). `color` is `Long` → test constructors use Long literals (`0xFF0000L`). `colorRgb` on `DanmakuItem` is `Int`, so assertions compare against Int literals.
