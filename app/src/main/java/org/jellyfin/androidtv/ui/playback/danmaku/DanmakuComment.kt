package org.jellyfin.androidtv.ui.playback.danmaku

/**
 * 弹幕显示模式
 */
enum class DanmakuMode {
    SCROLL,
    TOP,
    BOTTOM,
}

/**
 * 弹幕来源（依据 dandanplay 评论中发送者前缀区分）
 */
enum class DanmakuSource {
    DANDAN,
    BILIBILI,
    GAMER,
    OTHER,
}

/**
 * 单条弹幕数据
 */
data class DanmakuComment(
    /** 弹幕出现时间（秒） */
    val timeSeconds: Double,
    val mode: DanmakuMode,
    /** RGB 颜色，不含 alpha */
    val colorRgb: Int,
    val text: String,
    val source: DanmakuSource,
) {
    companion object {
        private const val COLOR_MASK = 0xFFFFFF
        private const val MIN_PARTS = 3

        /**
         * 解析 dandanplay 格式的弹幕参数
         *
         * @param p 格式："time,mode,color,user"
         * @param text 弹幕内容
         */
        @Suppress("MagicNumber", "ReturnCount")
        fun fromDandanplay(p: String, text: String): DanmakuComment? {
            if (text.isBlank()) return null
            val parts = p.split(',')
            if (parts.size < MIN_PARTS) return null

            val time = parts[0].toDoubleOrNull() ?: return null
            val mode = when (parts[1].trim().toIntOrNull()) {
                1, 6 -> DanmakuMode.SCROLL
                4 -> DanmakuMode.BOTTOM
                5 -> DanmakuMode.TOP
                else -> return null
            }
            val color = (parts[2].trim().toIntOrNull() ?: COLOR_MASK) and COLOR_MASK
            val user = parts.getOrElse(3) { "" }.trim()
            val source = when {
                user.startsWith("[BiliBili]") -> DanmakuSource.BILIBILI
                user.startsWith("[Gamer]") -> DanmakuSource.GAMER
                user.startsWith("[") -> DanmakuSource.OTHER
                else -> DanmakuSource.DANDAN
            }
            return DanmakuComment(
                timeSeconds = time,
                mode = mode,
                colorRgb = color,
                text = text.trim(),
                source = source,
            )
        }
    }
}
