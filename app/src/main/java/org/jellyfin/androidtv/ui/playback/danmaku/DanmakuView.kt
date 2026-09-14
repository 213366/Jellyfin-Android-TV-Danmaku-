package org.jellyfin.androidtv.ui.playback.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

/**
 * 原生弹幕渲染视图。由播放器当前进度驱动，暂停、倍速、seek 天然同步。
 */
@Suppress("TooManyFunctions")
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 播放位置提供者，返回当前播放进度（毫秒） */
    var positionProvider: (() -> Long)? = null

    var danmakuVisible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                forceRebuild = true
                invalidate()
            }
        }

    data class Config(
        val opacity: Float,
        val fontSizeSp: Int,
        val speedDpPerSecond: Int,
        val heightRatio: Float,
        val antiOverlap: Boolean,
    )

    private class RenderItem(
        val timeMs: Long,
        val mode: DanmakuMode,
        val colorRgb: Int,
        val text: String,
    ) {
        var width: Float = -1f
        var lane: Int = -1
    }

    private var config = Config(
        opacity = DanmakuPreferences.DEFAULT_OPACITY,
        fontSizeSp = DanmakuPreferences.DEFAULT_FONT_SIZE,
        speedDpPerSecond = DanmakuPreferences.DEFAULT_SPEED,
        heightRatio = DanmakuPreferences.DEFAULT_HEIGHT_RATIO,
        antiOverlap = false,
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeJoin = Paint.Join.ROUND
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var fontPx = 0f
    private var laneHeight = 0f
    private var speedPxPerSecond = 0f

    private var items: List<RenderItem> = emptyList()
    private val activeItems = ArrayList<RenderItem>()
    private var nextIndex = 0
    private var lastPositionMs = Long.MIN_VALUE
    private var forceRebuild = true

    private var laneCount = 1
    private var scrollLaneTimes = LongArray(0)
    private var scrollLaneWidths = FloatArray(0)
    private var topLaneUntil = LongArray(0)
    private var bottomLaneUntil = LongArray(0)

    private val frameRunnable = object : Runnable {
        override fun run() {
            tick()
            if (isAttachedToWindow) {
                postOnAnimation(this)
            }
        }
    }

    init {
        applyConfig(config)
    }

    fun setComments(comments: List<DanmakuComment>) {
        items = comments
            .map { comment ->
                RenderItem(
                    timeMs = (comment.timeSeconds * MILLIS_PER_SECOND).toLong(),
                    mode = comment.mode,
                    colorRgb = comment.colorRgb,
                    text = comment.text,
                )
            }
            .sortedBy(RenderItem::timeMs)
        forceRebuild = true
        invalidate()
    }

    fun applyConfig(newConfig: Config) {
        config = newConfig
        fontPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            newConfig.fontSizeSp.toFloat(),
            resources.displayMetrics,
        )
        laneHeight = fontPx * LINE_HEIGHT_FACTOR
        speedPxPerSecond = newConfig.speedDpPerSecond * resources.displayMetrics.density
        fillPaint.textSize = fontPx
        strokePaint.textSize = fontPx
        val alpha = (newConfig.opacity * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA)
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        items.forEach { item ->
            item.width = -1f
        }
        forceRebuild = true
        invalidate()
    }

    fun clear() {
        items = emptyList()
        activeItems.clear()
        nextIndex = 0
        forceRebuild = true
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        forceRebuild = true
    }

    private fun durationMs(): Long {
        if (width <= 0 || speedPxPerSecond <= 0f) return 0L
        return (width / speedPxPerSecond * MILLIS_PER_SECOND).toLong().coerceAtLeast(MIN_DURATION_MS)
    }

    private fun tick() {
        if (!danmakuVisible || items.isEmpty() || width == 0 || height == 0) {
            if (activeItems.isNotEmpty()) {
                activeItems.clear()
                invalidate()
            }
            return
        }
        val position = positionProvider?.invoke() ?: return
        val duration = durationMs()
        if (duration == 0L) return

        var changed = false

        if (forceRebuild || position < lastPositionMs - BACKWARD_TOLERANCE_MS ||
            position > lastPositionMs + FORWARD_JUMP_THRESHOLD_MS
        ) {
            rebuild(position, duration)
            changed = true
        }

        if (activeItems.removeAll { item -> position - item.timeMs > duration || position < item.timeMs }) {
            changed = true
        }

        while (nextIndex < items.size && items[nextIndex].timeMs <= position) {
            val item = items[nextIndex]
            nextIndex++
            if (position - item.timeMs > duration) continue
            if (item.width < 0f) {
                item.width = fillPaint.measureText(item.text)
            }
            item.lane = assignLane(item, duration)
            if (item.lane >= 0) {
                activeItems.add(item)
                changed = true
            }
        }

        if (changed || position != lastPositionMs) {
            invalidate()
        }
        lastPositionMs = position
    }

    private fun rebuild(position: Long, duration: Long) {
        forceRebuild = false
        activeItems.clear()
        resetLanes()
        var low = 0
        var high = items.size
        val minTime = position - duration
        while (low < high) {
            val mid = (low + high) ushr 1
            if (items[mid].timeMs < minTime) low = mid + 1 else high = mid
        }
        nextIndex = low
    }

    private fun resetLanes() {
        val usableHeight = height * config.heightRatio
        laneCount = max(1, floor(usableHeight / laneHeight).toInt())
        scrollLaneTimes = LongArray(laneCount) { Long.MIN_VALUE }
        scrollLaneWidths = FloatArray(laneCount)
        topLaneUntil = LongArray(laneCount) { Long.MIN_VALUE }
        bottomLaneUntil = LongArray(laneCount) { Long.MIN_VALUE }
    }

    @Suppress("ReturnCount")
    private fun assignLane(item: RenderItem, duration: Long): Int {
        if (scrollLaneTimes.size != laneCount) resetLanes()
        when (item.mode) {
            DanmakuMode.SCROLL -> {
                for (lane in 0 until laneCount) {
                    if (isScrollLaneFree(lane, item, duration)) {
                        scrollLaneTimes[lane] = item.timeMs
                        scrollLaneWidths[lane] = item.width
                        return lane
                    }
                }
                if (config.antiOverlap) return -1
                val lane = Random.nextInt(laneCount)
                scrollLaneTimes[lane] = item.timeMs
                scrollLaneWidths[lane] = item.width
                return lane
            }
            DanmakuMode.TOP -> return assignFixedLane(topLaneUntil, item, duration)
            DanmakuMode.BOTTOM -> return assignFixedLane(bottomLaneUntil, item, duration)
        }
    }

    private fun isScrollLaneFree(lane: Int, item: RenderItem, duration: Long): Boolean {
        val prevTime = scrollLaneTimes[lane]
        if (prevTime == Long.MIN_VALUE) return true
        val prevWidth = scrollLaneWidths[lane]
        val widthF = width.toFloat()
        val requiredGap = duration * max(
            prevWidth / (widthF + prevWidth),
            item.width / (widthF + item.width),
        )
        return item.timeMs - prevTime >= requiredGap
    }

    private fun assignFixedLane(laneUntil: LongArray, item: RenderItem, duration: Long): Int {
        for (lane in 0 until laneCount) {
            if (item.timeMs >= laneUntil[lane]) {
                laneUntil[lane] = item.timeMs + duration
                return lane
            }
        }
        if (config.antiOverlap) return -1
        val lane = Random.nextInt(laneCount)
        laneUntil[lane] = item.timeMs + duration
        return lane
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!danmakuVisible || activeItems.isEmpty()) return
        val position = lastPositionMs
        val duration = durationMs()
        if (duration == 0L) return
        val widthF = width.toFloat()

        val alpha = (config.opacity * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA)
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha

        for (item in activeItems) {
            val progress = (position - item.timeMs).toFloat() / duration
            if (progress < 0f || progress > 1f) continue

            val x: Float
            val baseline: Float
            when (item.mode) {
                DanmakuMode.SCROLL -> {
                    x = widthF - progress * (widthF + item.width)
                    baseline = item.lane * laneHeight + fontPx
                }
                DanmakuMode.TOP -> {
                    x = (widthF - item.width) / 2f
                    baseline = item.lane * laneHeight + fontPx
                }
                DanmakuMode.BOTTOM -> {
                    x = (widthF - item.width) / 2f
                    baseline = height - (item.lane + 1) * laneHeight + fontPx
                }
            }

            strokePaint.color = if (item.colorRgb == 0) Color.WHITE else Color.BLACK
            canvas.drawText(item.text, x, baseline, strokePaint)

            fillPaint.color = item.colorRgb or ALPHA_MASK
            canvas.drawText(item.text, x, baseline, fillPaint)
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000f
        private const val LINE_HEIGHT_FACTOR = 1.35f
        private const val STROKE_WIDTH = 3f
        private const val MAX_ALPHA = 255
        private const val ALPHA_MASK = 0xFF shl 24
        private const val MIN_DURATION_MS = 1000L
        private const val BACKWARD_TOLERANCE_MS = 250L
        private const val FORWARD_JUMP_THRESHOLD_MS = 2000L
    }
}
