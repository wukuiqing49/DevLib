package com.wkq.router.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * 路由请求载体，保存 path、参数、动画等信息。
 */
class Postcard(val path: String) {

    private var extras: Bundle = Bundle()
    private var enterAnim: Int = 0
    private var exitAnim: Int = 0
    private var flags: Int = -1

    fun withBundle(bundle: Bundle): Postcard {
        extras = bundle
        return this
    }

    fun withString(key: String, value: String?): Postcard {
        extras.putString(key, value)
        return this
    }

    fun withInt(key: String, value: Int): Postcard {
        extras.putInt(key, value)
        return this
    }

    fun withShort(key: String, value: Short): Postcard {
        extras.putShort(key, value)
        return this
    }

    fun withByte(key: String, value: Byte): Postcard {
        extras.putByte(key, value)
        return this
    }

    fun withChar(key: String, value: Char): Postcard {
        extras.putChar(key, value)
        return this
    }

    fun withLong(key: String, value: Long): Postcard {
        extras.putLong(key, value)
        return this
    }

    fun withBoolean(key: String, value: Boolean): Postcard {
        extras.putBoolean(key, value)
        return this
    }

    fun withFloat(key: String, value: Float): Postcard {
        extras.putFloat(key, value)
        return this
    }

    fun withDouble(key: String, value: Double): Postcard {
        extras.putDouble(key, value)
        return this
    }

    fun withSerializable(key: String, value: java.io.Serializable?): Postcard {
        extras.putSerializable(key, value)
        return this
    }

    fun withParcelable(key: String, value: android.os.Parcelable?): Postcard {
        extras.putParcelable(key, value)
        return this
    }

    fun withIntArray(key: String, value: IntArray?): Postcard {
        extras.putIntArray(key, value)
        return this
    }

    fun withShortArray(key: String, value: ShortArray?): Postcard {
        extras.putShortArray(key, value)
        return this
    }

    fun withByteArray(key: String, value: ByteArray?): Postcard {
        extras.putByteArray(key, value)
        return this
    }

    fun withCharArray(key: String, value: CharArray?): Postcard {
        extras.putCharArray(key, value)
        return this
    }

    fun withLongArray(key: String, value: LongArray?): Postcard {
        extras.putLongArray(key, value)
        return this
    }

    fun withBooleanArray(key: String, value: BooleanArray?): Postcard {
        extras.putBooleanArray(key, value)
        return this
    }

    fun withFloatArray(key: String, value: FloatArray?): Postcard {
        extras.putFloatArray(key, value)
        return this
    }

    fun withDoubleArray(key: String, value: DoubleArray?): Postcard {
        extras.putDoubleArray(key, value)
        return this
    }

    fun withStringArray(key: String, value: Array<String>?): Postcard {
        extras.putStringArray(key, value)
        return this
    }

    fun withStringArrayList(key: String, value: ArrayList<String>?): Postcard {
        extras.putStringArrayList(key, value)
        return this
    }

    fun withIntegerArrayList(key: String, value: ArrayList<Int>?): Postcard {
        extras.putIntegerArrayList(key, value)
        return this
    }

    fun withFlags(flags: Int): Postcard {
        this.flags = flags
        return this
    }

    fun withTransition(enterAnim: Int, exitAnim: Int): Postcard {
        this.enterAnim = enterAnim
        this.exitAnim = exitAnim
        return this
    }

    fun navigation(context: Context) {
        Router.navigate(context, this)
    }

    fun navigation(activity: FragmentActivity, callback: (ActivityResult) -> Unit) {
        Router.navigateWithResult(activity, this, callback)
    }

    fun getExtras() = extras
    fun getEnterAnim() = enterAnim
    fun getExitAnim() = exitAnim
    fun getFlags() = flags

    companion object {
        fun fromUri(uri: Uri): Postcard {
            val routePath = uri.path.orEmpty().ifBlank {
                val host = uri.host.orEmpty()
                if (host.isBlank()) "" else "/$host"
            }
            return Postcard(routePath).apply {
                uri.queryParameterNames.forEach { name ->
                    withString(name, uri.getQueryParameter(name))
                }
            }
        }
    }
}

/**
 * 内部代理 Fragment，用于承接 ActivityResult。
 */
class RouterResultProxyFragment : Fragment() {
    private var callback: ((ActivityResult) -> Unit)? = null
    private var intent: Intent? = null

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        callback?.invoke(result)
        parentFragmentManager.beginTransaction()
            .remove(this)
            .commitAllowingStateLoss()
    }

    fun setParams(intent: Intent, callback: (ActivityResult) -> Unit) {
        this.intent = intent
        this.callback = callback
    }

    override fun onResume() {
        super.onResume()
        val targetIntent = intent ?: return
        intent = null
        try {
            launcher.launch(targetIntent)
        } catch (t: Throwable) {
            RouterConfig.logger.e("Launch route for result failed.", t)
            parentFragmentManager.beginTransaction()
                .remove(this)
                .commitAllowingStateLoss()
        }
    }
}
