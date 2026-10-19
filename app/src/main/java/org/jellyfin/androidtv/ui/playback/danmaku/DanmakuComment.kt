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
 * 弹幕来源（依据弹幕属性中的发送者/来源前缀区分）
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
         * 解析 misaka / dandanplay 通用的弹幕参数
         *
         * @param p 格式："time,mode,color,sender"，例如 "1.665,1,16777215,[bilibili]"
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
                user.startsWith("[bilibili]", ignoreCase = true) -> DanmakuSource.BILIBILI
                user.startsWith("[gamer]", ignoreCase = true) -> DanmakuSource.GAMER
                user.startsWith("[dandanplay]", ignoreCase = true) -> DanmakuSource.DANDAN
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
