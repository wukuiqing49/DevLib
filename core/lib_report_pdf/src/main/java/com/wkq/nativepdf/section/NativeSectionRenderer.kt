package com.wkq.nativepdf.section

import com.wkq.nativepdf.model.NativeReportData
import com.wkq.nativepdf.render.NativePdfPageManager

internal interface NativeSectionRenderer {
    fun render(page: NativePdfPageManager, data: NativeReportData)
}
