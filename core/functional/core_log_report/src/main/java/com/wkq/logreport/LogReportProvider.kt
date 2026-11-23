package com.wkq.logreport

/** 独立附件提供器，避免与宿主 FileProvider 合并冲突。 */
class LogReportProvider : androidx.core.content.FileProvider()
