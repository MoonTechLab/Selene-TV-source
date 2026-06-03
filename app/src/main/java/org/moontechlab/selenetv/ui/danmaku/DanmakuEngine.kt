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
                    d.y = usableHeight() - d.lane * laneH
                    nowMs - d.spawnTimeMs > config.topBottomDurationMs
                }
            }
            if (expired) { recycle(d); activeList.removeAt(i) } else i++
        }
    }

    // ---- internals ----

    private fun laneHeight(): Float = config.textSizePx * config.lineHeightFactor

    /** 可用高度 = 视图高 × 显示区域比例（弹幕只占顶部这一带）。 */
    private fun usableHeight(): Float = viewH * config.areaFraction.coerceIn(0f, 1f)

    private fun laneCount(): Int {
        val lh = laneHeight()
        if (lh <= 0f) return 1
        return maxOf(1, (usableHeight() / lh).toInt())
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
