package com.wkq.dev.consumer

import com.wkq.photo.imagerender.ImageSource
import com.wkq.photo.pdf.PdfOperationRequest
import com.wkq.photo.pdf.PdfOutputTarget
import com.wkq.photo.qrcode.QrLiveScanActivity
import java.io.File

/** 检查保留库的 POM 是否暴露 PDF 与二维码公开签名所需类型。 */
class PublicBoundarySmoke {
    fun firebaseUser(): com.google.firebase.auth.FirebaseUser? = com.wkq.google.auth.DropGoogleAccount.currentUser
    fun billingConfig() = com.wkq.google.billing.GoogleBillingRuntimeConfig()
    fun pdf(file: File, output: File) = PdfOperationRequest(listOf(ImageSource.FileSource(file)), PdfOutputTarget.FileTarget(output))
    fun scanner(activity: QrLiveScanActivity) = activity.finish()
}
