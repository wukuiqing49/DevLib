package com.wkq.dev.consumer

import com.wkq.photo.imagerender.ImageSource
import com.wkq.photo.pdf.PdfOperationRequest
import com.wkq.photo.pdf.PdfOutputTarget
import com.wkq.photo.qrcode.QrLiveScanActivity
import java.io.File

/** 检查保留库的 POM 是否暴露 PDF 与二维码公开签名所需类型。 */
class PublicBoundarySmoke {
    fun uploadTask(uri: String) = com.wkq.iptc.upload.UploadTask(0, uri, "sample.png")
    /** 检查 NAS 公开模型和会话接口不要求消费方直接依赖协议实现。 */
    fun nas(connection: com.wkq.naslib.NasConnection): com.wkq.naslib.NasStorage = com.wkq.naslib.NasClient.open(connection)
    /** 检查 USB 公开接口只要求 Android Context 与授权树 URI。 */
    fun usb(context: android.content.Context, tree: String) = com.wkq.usb.UsbStorage(context, tree)
    /** 验证评分状态公开签名不要求宿主直接引入 Play Review SDK。 */
    suspend fun reviewState(context: android.content.Context): com.wkq.google.rate.GoogleRateState = com.wkq.google.rate.GoogleRateManager.snapshot(context)
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
