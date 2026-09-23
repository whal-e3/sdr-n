package org.satelliteeavesdropper.app.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class Ax25FormatterTest {
    @Test fun formatsAprsUiFrame() {
        fun address(call: String, ssid: Int, last: Boolean): ByteArray {
            val padded = call.padEnd(6, ' ')
            val bytes = ByteArray(7)
            for (i in 0 until 6) bytes[i] = (padded[i].code shl 1).toByte()
            bytes[6] = (0x60 or (ssid shl 1) or if (last) 1 else 0).toByte()
            return bytes
        }
        val frame = address("APRS", 0, false) + address("N0CALL", 7, true) +
            byteArrayOf(0x03, 0xf0.toByte()) + "hello".toByteArray()
        assertEquals("N0CALL-7>APRS:hello", Ax25Formatter.format(frame))
    }

    @Test fun rejectsTruncatedAddress() {
        assertEquals("AX.25 frame (3 bytes)", Ax25Formatter.format(byteArrayOf(1, 2, 3)))
    }
}
