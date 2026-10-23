package com.beautysdk.rfb

import org.junit.Assert.*
import org.junit.Test

class RfbPostprocessorTest {
    @Test fun selectsBestValidBoxAndClamps() {
        val box = RfbPostprocessor.best(floatArrayOf(.99f,.9f,.8f), listOf(
            floatArrayOf(.9f,.2f,.1f,.8f), floatArrayOf(-.1f,.1f,1.1f,.9f), floatArrayOf(.2f,.2f,.4f,.4f)), .7f)
        assertEquals(0f,box!!.left,0f); assertEquals(1f,box.right,0f)
    }
    @Test fun rejectsNoFaceAndNan() {
        assertNull(RfbPostprocessor.best(floatArrayOf(.2f, Float.NaN), listOf(
            floatArrayOf(0f,0f,1f,1f),floatArrayOf(0f,0f,1f,1f)), .7f))
    }
}
