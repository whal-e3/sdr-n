package org.satelliteeavesdropper.app.receiver

/** Human-readable display for a CRC-checked AX.25 frame body (FCS already removed). */
object Ax25Formatter {
    fun format(frame: ByteArray): String {
        if (frame.size < 16) return "AX.25 frame (${frame.size} bytes)"
        val addresses = mutableListOf<String>()
        var index = 0
        do {
            if (index + 7 > frame.size || addresses.size >= 10) return "AX.25 frame (${frame.size} bytes)"
            val call = (0 until 6).map { position ->
                ((frame[index + position].toInt() and 0xff) ushr 1).toChar()
            }.joinToString("").trim()
            if (call.isEmpty() || call.any { it !in ' '..'~' }) return "AX.25 frame (${frame.size} bytes)"
            val ssidByte = frame[index + 6].toInt() and 0xff
            val ssid = (ssidByte ushr 1) and 0x0f
            addresses += if (ssid == 0) call else "$call-$ssid"
            index += 7
        } while ((ssidByte and 1) == 0)
        if (addresses.size < 2 || index >= frame.size) return "AX.25 frame (${frame.size} bytes)"
        val path = buildString {
            append(addresses[1]).append('>').append(addresses[0])
            addresses.drop(2).forEach { append(',').append(it) }
        }
        val control = frame[index].toInt() and 0xff
        if (control != 0x03 || index + 1 >= frame.size) {
            return "$path:AX.25 control 0x${control.toString(16).padStart(2, '0')}"
        }
        val pid = frame[index + 1].toInt() and 0xff
        if (pid != 0xf0) return "$path:AX.25 PID 0x${pid.toString(16).padStart(2, '0')}"
        val text = frame.copyOfRange(index + 2, frame.size).take(512).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            if (value in 32..126) value.toChar().toString() else "·"
        }
        return "$path:$text"
    }
}
