package com.wkq.dev.consumer

import com.wkq.photo.imagerender.ImageSource
import com.wkq.photo.pdf.PdfOperationRequest
import com.wkq.photo.pdf.PdfOutputTarget
import com.wkq.photo.qrcode.QrLiveScanActivity
import java.io.File

/** 检查保留库的 POM 是否暴露 PDF 与二维码公开签名所需类型。 */
class PublicBoundarySmoke {
    fun uploadTask(uri: String) = com.wkq.iptc.upload.UploadTask(0, uri, "sample.png")
    fun persistentTasks(context: android.content.Context): kotlinx.coroutines.flow.Flow<List<com.wkq.nas.transfer.TaskSnapshot>> =
        com.wkq.nas.transfer.TransferClient.get(context).observeTasks()
    fun metadataInspector() = com.wkq.util.exif.ImageMetadataInspector
    fun imageSource(file: File): ImageSource = ImageSource.FileSource(file)
    fun operationBudget() = com.wkq.photo.core.operation.OperationBudget.DEFAULT
    fun archiveWriter() = com.wkq.photo.archive.ZipArchiveCreator
    fun utilities() = com.wkq.util.FileUtil
    fun firebaseUser(): com.google.firebase.auth.FirebaseUser? = com.wkq.google.auth.DropGoogleAccount.currentUser
    fun billingConfig() = com.wkq.google.billing.GoogleBillingRuntimeConfig()
    fun pdf(file: File, output: File) = PdfOperationRequest(listOf(ImageSource.FileSource(file)), PdfOutputTarget.FileTarget(output))
    fun scanner(activity: QrLiveScanActivity) = activity.finish()
}
