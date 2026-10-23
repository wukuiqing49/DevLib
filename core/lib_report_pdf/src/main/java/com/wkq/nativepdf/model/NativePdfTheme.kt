package com.wkq.nativepdf.model

import android.graphics.Color
import android.graphics.Typeface

data class NativePdfTheme(
    val primaryColor: Int,
    val accentColor: Int,
    val dangerColor: Int,
    val warningColor: Int,
    val successColor: Int,
    val titleColor: Int,
    val bodyColor: Int,
    val mutedColor: Int,
    val lineColor: Int,
    val fillColor: Int,
    val pageBackgroundColor: Int = Color.WHITE,
    val typeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
    val titleTypeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
) {
    companion object {
        fun engineering(): NativePdfTheme = NativePdfTheme(
            primaryColor = Color.rgb(15, 92, 192),
            accentColor = Color.rgb(20, 184, 166),
            dangerColor = Color.rgb(220, 38, 38),
            warningColor = Color.rgb(217, 119, 6),
            successColor = Color.rgb(5, 150, 105),
            titleColor = Color.rgb(17, 24, 39),
            bodyColor = Color.rgb(31, 41, 55),
            mutedColor = Color.rgb(100, 116, 139),
            lineColor = Color.rgb(216, 222, 232),
            fillColor = Color.rgb(248, 250, 252)
        )

        fun acceptance(): NativePdfTheme = NativePdfTheme(
            primaryColor = Color.rgb(15, 118, 110),
            accentColor = Color.rgb(245, 158, 11),
            dangerColor = Color.rgb(185, 28, 28),
            warningColor = Color.rgb(217, 119, 6),
            successColor = Color.rgb(4, 120, 87),
            titleColor = Color.rgb(15, 23, 42),
            bodyColor = Color.rgb(30, 41, 59),
            mutedColor = Color.rgb(100, 116, 139),
            lineColor = Color.rgb(153, 246, 228),
            fillColor = Color.rgb(240, 253, 250)
        )

        fun client(): NativePdfTheme = NativePdfTheme(
            primaryColor = Color.rgb(17, 24, 39),
            accentColor = Color.rgb(37, 99, 235),
            dangerColor = Color.rgb(220, 38, 38),
            warningColor = Color.rgb(202, 138, 4),
            successColor = Color.rgb(22, 101, 52),
            titleColor = Color.rgb(17, 24, 39),
            bodyColor = Color.rgb(31, 41, 55),
            mutedColor = Color.rgb(107, 114, 128),
            lineColor = Color.rgb(209, 213, 219),
            fillColor = Color.rgb(249, 250, 251)
        )
    }
}
