package com.wkq.iptc.upload

data class UploadTask(
    val recordId: Long,
    val recordUri: String,
    val fileName: String,
    /** 断点续传起始偏移（字节），0 表示从头上传。 */
    val resumeOffset: Long = 0L,
    /** 新建交付文件，禁止覆盖；旧调用方默认保留原行为。 */
    val createOnly: Boolean = false,
    val transferId: String = ""
)

