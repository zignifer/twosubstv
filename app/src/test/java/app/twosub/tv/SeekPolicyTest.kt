package app.twosub.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPolicyTest {
    @Test fun holdAcceleratesAfterDeliberateThresholds() {
        assertEquals(10.0, SeekPolicy.stepSeconds(0), 0.0)
        assertEquals(10.0, SeekPolicy.stepSeconds(1_499), 0.0)
        assertEquals(30.0, SeekPolicy.stepSeconds(1_500), 0.0)
        assertEquals(30.0, SeekPolicy.stepSeconds(3_999), 0.0)
        assertEquals(60.0, SeekPolicy.stepSeconds(4_000), 0.0)
    }

    @Test fun previewCannotCrossEitherEndOrUseUnknownDuration() {
        assertEquals(0.0, SeekPolicy.position(-10.0, 100.0), 0.0)
        assertEquals(99.75, SeekPolicy.position(200.0, 100.0), 0.0)
        assertEquals(12.0, SeekPolicy.position(12.0, 100.0), 0.0)
        assertEquals(0.0, SeekPolicy.position(20.0, Double.NaN), 0.0)
        assertEquals(0.0, SeekPolicy.position(Double.POSITIVE_INFINITY, 100.0), 0.0)
        assertEquals(0.0, SeekPolicy.position(10.0, .1), 0.0)
    }

    @Test fun fastTapsAccumulateBeforeThePlayerPositionUpdates() {
        val cursor = SeekCursor()
        repeat(13) { cursor.move(157.0, 1653.0, -1, 0) }
        assertEquals(27.0, cursor.seconds!!, 0.0)
        cursor.reset()
        assertEquals(90.0, cursor.move(80.0, 1653.0, 1, 0), 0.0)
    }
}
