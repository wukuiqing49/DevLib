package com.devhub.sdk

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** SDK-owned activity launched with DevHub.campaignsIntent(). */
class CampaignsActivity : Activity() {
  private lateinit var sessionId: String
  private lateinit var sdk: DevHub
  private lateinit var content: LinearLayout
  private var campaigns = emptyList<JSONObject>()
  private var selected: JSONObject? = null
  private var restoreCampaignId: String? = null
  private var loadVersion = 0

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    sessionId = intent.getStringExtra("devhub_instance").orEmpty()
    val found = DevHub.campaignInstance(sessionId)
    if (found == null) { finish(); return }
    sdk = found
    restoreCampaignId = state?.getString("selected_campaign_id")
    content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(16), dp(20), dp(28))
    }
    setContentView(ScrollView(this).apply { addView(content) })
    showLoading()
    load(false)
  }

  override fun onDestroy() {
    loadVersion++
    super.onDestroy()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    selected?.optString("id")?.let { outState.putString("selected_campaign_id", it) }
    super.onSaveInstanceState(outState)
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (selected != null) { selected = null; showList() } else super.onBackPressed()
  }

  private fun load(force: Boolean) {
    val version = ++loadVersion
    sdk.loadCampaignsResult(force) { result ->
      if (isFinishing || isDestroyed || version != loadVersion) return@loadCampaignsResult
      if (result.successful && result.value != null) {
        campaigns = (0 until result.value.length()).mapNotNull { result.value.optJSONObject(it) }
        selected = campaigns.find { it.optString("id") == restoreCampaignId }
        restoreCampaignId = null
        if (selected != null) showDetail(selected!!)
        else showList(if (result.stale) "当前显示的是缓存活动" else null)
      } else {
        content.removeAllViews()
        title("活动")
        label("暂时无法加载活动")
        command("重试") { showLoading(); load(true) }
      }
    }
  }

  private fun showLoading() {
    content.removeAllViews()
    title("活动")
    label("正在加载...")
  }

  private fun showList(note: String? = null) {
    content.removeAllViews()
    title("活动")
    command("刷新") { load(true) }
    if (note != null) label(note)
    if (campaigns.isEmpty()) label("暂无活动")
    campaigns.forEach { campaign ->
      val item = TextView(this).apply {
        text = campaign.optString("name")
        textSize = 18f
        setTextColor(0xff202a29.toInt())
        setPadding(dp(12), dp(16), dp(12), dp(16))
        setBackgroundColor(0xfff1f5f4.toInt())
        setOnClickListener { selected = campaign; showDetail(campaign) }
      }
      content.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
  }

  private fun showDetail(campaign: JSONObject) {
    content.removeAllViews()
    command("返回") { selected = null; showList() }
    title(campaign.optString("name"))
    label("正在加载...")
    sdk.loadCampaignDetail(campaign.optString("id")) { result ->
      if (isFinishing || isDestroyed || selected !== campaign) return@loadCampaignDetail
      if (result.successful && result.value != null) renderDetail(campaign, result.value)
      else {
        content.removeAllViews()
        command("返回") { selected = null; showList() }
        title(campaign.optString("name"))
        label("详情暂时无法加载")
        command("重试") { showDetail(campaign) }
      }
    }
  }

  private fun renderDetail(campaign: JSONObject, detail: JSONObject) {
    content.removeAllViews()
    command("返回") { selected = null; showList() }
    title(campaign.optString("name"))
    val imagePath = campaign.optString("imageUrl")
    if (imagePath.isNotBlank()) {
      val image = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = campaign.optString("name")
      }
      content.addView(image, LinearLayout.LayoutParams(-1, dp(220)))
      sdk.loadCampaignImage(imagePath) { result ->
        val bytes = result.value ?: return@loadCampaignImage
        Thread {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          var sample = 1
          while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
          val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
          runOnUiThread {
            if (!isFinishing && !isDestroyed && selected === campaign && bitmap != null) image.setImageBitmap(bitmap)
          }
        }.start()
      }
    }
    label(detail.optString("pageContent").takeIf { it.isNotBlank() } ?: campaign.optString("content"))
    val type = when (campaign.optString("type")) {
      "announcement" -> CampaignType.ANNOUNCEMENT
      "promotion" -> CampaignType.PROMOTION
      "upgrade" -> CampaignType.UPGRADE
      else -> null
    }
    if (type != null) {
      val actionText = detail.optString("pageButtonText").takeIf { it.isNotBlank() } ?: "查看详情"
      command(actionText) {
        setResult(RESULT_OK, android.content.Intent().putExtra("campaignId", campaign.optString("id")).putExtra("campaignType", type.name))
        finish()
      }
    }
    if (type == CampaignType.PROMOTION && detail.optBoolean("canRedeem")) {
      val code = EditText(this).apply { hint = "优惠码"; setSingleLine(true) }
      content.addView(code, LinearLayout.LayoutParams(-1, -2))
      val status = label("")
      lateinit var redeem: Button
      redeem = command("兑换") {
        redeem.isEnabled = false
        status.text = "正在兑换..."
        sdk.redeemCampaignCoupon(campaign.optString("id"), code.text.toString()) { result ->
          if (isFinishing || isDestroyed || selected !== campaign) return@redeemCampaignCoupon
          redeem.isEnabled = true
          val redeemed = result.successful && result.value?.optBoolean("redeemed") == true
          status.text = if (redeemed) "兑换成功" else "兑换失败，请检查优惠码后重试"
          if (redeemed) {
            redeem.isEnabled = false
            sdk.loadEntitlementSnapshot { }
          }
        }
      }
    }
  }

  private fun title(value: String) {
    content.addView(TextView(this).apply {
      text = value
      textSize = 24f
      setTextColor(0xff202a29.toInt())
      setPadding(0, dp(4), 0, dp(16))
    })
  }

  private fun label(value: String): TextView = TextView(this).also {
    it.text = value
    it.textSize = 16f
    it.setTextColor(0xff344440.toInt())
    it.setPadding(0, dp(10), 0, dp(14))
    content.addView(it)
  }

  private fun command(value: String, action: () -> Unit): Button = Button(this).also {
    it.text = value
    it.gravity = Gravity.CENTER
    it.setOnClickListener { action() }
    content.addView(it, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
