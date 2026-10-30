package com.wkq.photo.qrcode

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.zxing.Result
import com.king.camera.scan.AnalyzeResult
import com.king.zxing.BarcodeCameraScanActivity
import com.king.zxing.analyze.QRCodeAnalyzer

/** ZXingLite 连续扫码入口，对业务层隐藏第三方扫码类型。 */
class QrLiveScanActivity : BarcodeCameraScanActivity() {
    private var delivered = false

    /** 使用既有扫码分析器，权限及相机生命周期沿用第三方实现。 */
    override fun createAnalyzer() = QRCodeAnalyzer()

    /** 单个页面只返回一次识别结果，忽略结束过程中的重复回调。 */
    override fun onScanResultCallback(result: AnalyzeResult<Result>) {
        val content = result.result.text?.trim().orEmpty()
        if (content.isEmpty() || delivered || isFinishing || isDestroyed) return
        delivered = true
        cameraScan.setAnalyzeImage(false)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, content))
        finish()
    }

    companion object {
        private const val EXTRA_RESULT = "com.wkq.photo.qrcode.RESULT"

        fun intent(context: Context): Intent = Intent(context, QrLiveScanActivity::class.java)
        fun result(data: Intent?): String? = data?.getStringExtra(EXTRA_RESULT)?.takeIf(String::isNotBlank)
    }
}
