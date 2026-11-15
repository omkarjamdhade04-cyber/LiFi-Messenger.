package com.lifi.messenger

/**
 * Li-Fi light protocol - MUST match LiFi_Receiver.ino.
 *
 * Torch ON = 1, torch OFF = 0, idle = OFF.
 * Every byte is sent as a frame: START(1) + 8 data bits (MSB first) + STOP(0).
 * Packet bytes: 00 00 00 | 02 (STX) | LEN | payload (ASCII) | CRC8 | 03 (ETX)
 *  - the three 0x00 training bytes give three short pulses exactly 10 bit-times
 *    apart, from which the Arduino measures the bit time automatically
 *  - CRC8 (polynomial 0x07) covers LEN + payload
 */
object LiFiProtocol {
    const val MAX_MSG = 100
    const val LEAD_IDLE_BITS = 3          // torch stays OFF this long before the first frame
    private const val STX = 0x02
    private const val ETX = 0x03
    private const val TRAINING_BYTES = 3
    private const val OVERHEAD_BYTES = TRAINING_BYTES + 4   // training + STX + LEN + CRC + ETX

    /** The LCD and the 8-bit ASCII link support printable characters 32..126. */
    fun isSupported(text: String): Boolean = text.all { it.code in 32..126 }

    /** Line breaks become spaces (the LCD has no line breaks). */
    fun sanitize(text: String): String = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    fun buildPacket(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.US_ASCII)
        require(payload.isNotEmpty() && payload.size <= MAX_MSG) { "Message must be 1..$MAX_MSG characters" }
        val body = byteArrayOf(payload.size.toByte()) + payload
        val crc = crc8(body)
        return ByteArray(TRAINING_BYTES) + byteArrayOf(STX.toByte()) + body + byteArrayOf(crc.toByte(), ETX.toByte())
    }

    /** Light on/off level for every bit time (true = torch ON). */
    fun toBits(packet: ByteArray): BooleanArray {
        val bits = BooleanArray(LEAD_IDLE_BITS + packet.size * 10)
        var i = LEAD_IDLE_BITS
        for (b in packet) {
            val v = b.toInt() and 0xFF
            bits[i++] = true                                  // START
            for (k in 7 downTo 0) bits[i++] = ((v shr k) and 1) == 1
            bits[i++] = false                                 // STOP
        }
        return bits
    }

    /** "H" -> "01001000" ... shown in the app. */
    fun binaryString(text: String): String =
        text.map { it.code.toString(2).padStart(8, '0') }.joinToString(" ")

    fun totalBits(chars: Int): Int = LEAD_IDLE_BITS + (chars + OVERHEAD_BYTES) * 10

    fun durationMs(chars: Int, bitMs: Int): Long = totalBits(chars).toLong() * bitMs
}
