package app.twosub.tv

/** A held remote key accelerates the preview, never the video decoder or network. */
object SeekPolicy {
    const val REPEAT_INTERVAL_MS = 200L

    fun stepSeconds(heldMs: Long): Double = when {
        heldMs >= 4_000 -> 60.0
        heldMs >= 1_500 -> 30.0
        else -> 10.0
    }

    fun position(seconds: Double, duration: Double): Double {
        if (!duration.isFinite() || duration <= 0 || !seconds.isFinite()) return 0.0
        return seconds.coerceIn(0.0, (duration - .25).coerceAtLeast(0.0))
    }
}

/** Accumulate fast key events even before Compose has rendered the preceding event. */
class SeekCursor {
    var seconds: Double? = null
        private set

    fun move(current: Double, duration: Double, direction: Int, heldMs: Long): Double =
        SeekPolicy.position((seconds ?: current) + direction * SeekPolicy.stepSeconds(heldMs), duration).also { seconds = it }

    fun reset() { seconds = null }
}
