package com.starxen.live.media

/** Business APIs use UInt32 decimals; Android SDK calls/callbacks carry the same bits in Int. */
object MediaUid {
    @JvmStatic fun parse(value: String?): Int? = value
        ?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
        ?.toLongOrNull()?.takeIf { it in 1L..0xffff_ffffL }?.toInt()

    @JvmStatic fun toWire(value: Int): Long {
        require(value != 0) { "An explicitly authorized media UID is required" }
        return value.toLong() and 0xffff_ffffL
    }
}
