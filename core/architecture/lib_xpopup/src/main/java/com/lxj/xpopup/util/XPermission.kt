package com.lxj.xpopup.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.lxj.xpopup.util.PermissionConstants.getPermissions
import java.util.Arrays

/**
 * Description: copy from https://github.com/Blankj/AndroidUtilCode
 * Create by dance, at 2019/4/1
 */
class XPermission private constructor(context: Context?, vararg permissions: String?) {
    private var context: Context? = context?.applicationContext
    private var mOnRationaleListener: OnRationaleListener? = null
    private var mSimpleCallback: SimpleCallback? = null
    private var mFullCallback: FullCallback? = null
    private var mThemeCallback: ThemeCallback? = null
    private var mPermissions: MutableSet<String>? = null
    private var mPermissionsRequest: MutableList<String>? = null
    private var mPermissionsGranted: MutableList<String?>? = null
    private var mPermissionsDenied: MutableList<String?>? = null
    private var mPermissionsDeniedForever: MutableList<String?>? = null

    val permissions: MutableList<String?>
        /**
         * Return the permissions used in application.
         *
         * @return the permissions used in application
         */
        get() = getPermissions(context!!.getPackageName())

    /**
     * Return the permissions used in application.
     *
     * @param packageName The name of the package.
     * @return the permissions used in application
     */
    fun getPermissions(packageName: String): MutableList<String?> {
        val pm = context!!.getPackageManager()
        try {
            val permissions = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
            if (permissions == null) {
                return mutableListOf<String?>()
            }
            return Arrays.asList<String?>(*permissions)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            return mutableListOf<String?>()
        }
    }

    /**
     * Return whether *you* have been granted the permissions.
     *
     * @param permissions The permissions.
     * @return `true`: yes<br></br>`false`: no
     */
    fun isGranted(vararg permissions: String): Boolean {
        for (permission in permissions) {
            if (!isGranted(permission)) {
                return false
            }
        }
        return true
    }

    private fun isGranted(permission: String): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || (PackageManager.PERMISSION_GRANTED
                == ContextCompat.checkSelfPermission(context!!, permission))
    }

    @get:RequiresApi(api = Build.VERSION_CODES.M)
    val isGrantedWriteSettings: Boolean
        /**
         * Return whether the app can modify system settings.
         *
         * @return `true`: yes<br></br>`false`: no
         */
        get() = Settings.System.canWrite(context)

    @RequiresApi(api = Build.VERSION_CODES.M)
    fun requestWriteSettings(callback: SimpleCallback?) {
        if (this.isGrantedWriteSettings) {
            if (callback != null) callback.onGranted()
            return
        }
        sSimpleCallback4WriteSettings = callback
        PermissionActivity.Companion.start(
            context!!,
            PermissionActivity.Companion.TYPE_WRITE_SETTINGS
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun startWriteSettingsActivity(activity: Activity, requestCode: Int) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        intent.setData(Uri.parse("package:" + context!!.getPackageName()))
        if (!isIntentAvailable(intent)) {
            launchAppDetailsSettings()
            return
        }
        activity.startActivityForResult(intent, requestCode)
    }

    val isGrantedDrawOverlays: Boolean
        /**
         * Return whether the app can draw on top of other apps.
         *
         * @return `true`: yes<br></br>`false`: no
         */
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(context)
            } else return true
        }

    @RequiresApi(api = Build.VERSION_CODES.M)
    fun requestDrawOverlays(callback: SimpleCallback?) {
        if (this.isGrantedDrawOverlays) {
            if (callback != null) callback.onGranted()
            return
        }
        sSimpleCallback4DrawOverlays = callback
        PermissionActivity.Companion.start(
            context!!,
            PermissionActivity.Companion.TYPE_DRAW_OVERLAYS
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun startOverlayPermissionActivity(activity: Activity, requestCode: Int) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.setData(Uri.parse("package:" + context!!.getPackageName()))
        if (!isIntentAvailable(intent)) {
            launchAppDetailsSettings()
            return
        }
        activity.startActivityForResult(intent, requestCode)
    }

    /**
     * Launch the application's details settings.
     */
    fun launchAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.setData(Uri.parse("package:" + context!!.getPackageName()))
        if (!isIntentAvailable(intent)) return
        context!!.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isIntentAvailable(intent: Intent): Boolean {
        return context!!
            .getPackageManager()
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .size > 0
    }

    init {
        instance = this
        prepare(*permissions)
    }

    private fun prepare(vararg permissions: String?) {
        mPermissions = LinkedHashSet<String>()
        PERMISSIONS = this.permissions
        for (permission in permissions) {
            for (aPermission in getPermissions(permission)) {
                if (PERMISSIONS!!.contains(aPermission)) {
                    mPermissions!!.add(aPermission!!)
                }
            }
        }
    }

    /**
     * Set rationale listener.
     *
     * @param listener The rationale listener.
     */
    fun rationale(listener: OnRationaleListener?): XPermission {
        mOnRationaleListener = listener
        return this
    }

    /**
     * Set the simple call back.
     *
     * @param callback the simple call back
     */
    fun callback(callback: SimpleCallback?): XPermission {
        mSimpleCallback = callback
        return this
    }

    /**
     * Set the full call back.
     *
     * @param callback the full call back
     */
    fun callback(callback: FullCallback?): XPermission {
        mFullCallback = callback
        return this
    }

    /**
     * Start request.
     */
    fun request() {
        mPermissionsGranted = ArrayList<String?>()
        mPermissionsRequest = ArrayList<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mPermissionsGranted!!.addAll(mPermissions!!)
            requestCallback()
        } else {
            for (permission in mPermissions!!) {
                if (isGranted(permission)) {
                    mPermissionsGranted!!.add(permission)
                } else {
                    mPermissionsRequest!!.add(permission)
                }
            }
            if (mPermissionsRequest!!.isEmpty()) {
                requestCallback()
            } else {
                startPermissionActivity()
            }
        }
    }

    fun releaseContext() {
        context = null
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun startPermissionActivity() {
        mPermissionsDenied = ArrayList<String?>()
        mPermissionsDeniedForever = ArrayList<String?>()
        PermissionActivity.Companion.start(context!!, PermissionActivity.Companion.TYPE_RUNTIME)
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun rationale(activity: Activity): Boolean {
        var isRationale = false
        if (mOnRationaleListener != null) {
            for (permission in mPermissionsRequest!!) {
                if (activity.shouldShowRequestPermissionRationale(permission)) {
                    getPermissionsStatus(activity)
                    mOnRationaleListener!!.rationale(object : OnRationaleListener.ShouldRequest {
                        override fun again(again: Boolean) {
                            if (again) {
                                startPermissionActivity()
                            } else {
                                requestCallback()
                            }
                        }
                    })
                    isRationale = true
                    break
                }
            }
            mOnRationaleListener = null
        }
        return isRationale
    }

    private fun getPermissionsStatus(activity: Activity) {
        for (permission in mPermissionsRequest!!) {
            if (isGranted(permission)) {
                mPermissionsGranted!!.add(permission)
            } else {
                mPermissionsDenied!!.add(permission)
                if (!activity.shouldShowRequestPermissionRationale(permission)) {
                    mPermissionsDeniedForever!!.add(permission)
                }
            }
        }
    }

    private fun requestCallback() {
        if (mSimpleCallback != null) {
            if (mPermissionsRequest!!.size == 0
                || mPermissions!!.size == mPermissionsGranted!!.size
            ) {
                mSimpleCallback!!.onGranted()
            } else {
                if (!mPermissionsDenied!!.isEmpty()) {
                    mSimpleCallback!!.onDenied()
                }
            }
            mSimpleCallback = null
        }
        if (mFullCallback != null) {
            if (mPermissionsRequest!!.size == 0
                || mPermissions!!.size == mPermissionsGranted!!.size
            ) {
                mFullCallback!!.onGranted(mPermissionsGranted)
            } else {
                if (!mPermissionsDenied!!.isEmpty()) {
                    mFullCallback!!.onDenied(mPermissionsDeniedForever, mPermissionsDenied)
                }
            }
            mFullCallback = null
        }
        mOnRationaleListener = null
        mThemeCallback = null
    }

    private fun onRequestPermissionsResult(activity: Activity) {
        getPermissionsStatus(activity)
        requestCallback()
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    class PermissionActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            getWindow().addFlags(
                (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            )
            getWindow().getAttributes().alpha = 0f
            val byteExtra = getIntent().getIntExtra(TYPE, TYPE_RUNTIME)
            if (byteExtra == TYPE_RUNTIME) {
                val permission = instance
                if (permission == null) {
                    super.onCreate(savedInstanceState)
                    Log.e("XPermission", "request permissions failed")
                    finish()
                    return
                }
                if (permission.mThemeCallback != null) {
                    permission.mThemeCallback!!.onActivityCreate(this)
                }
                super.onCreate(savedInstanceState)

                if (permission.rationale(this)) {
                    finish()
                    return
                }
                if (permission.mPermissionsRequest != null) {
                    val size: Int = permission.mPermissionsRequest!!.size
                    if (size <= 0) {
                        finish()
                        return
                    }
                    val permissionsRequest = permission.mPermissionsRequest ?: return
                    requestPermissions(permissionsRequest.toTypedArray(), 1)
                }
            } else if (byteExtra == TYPE_WRITE_SETTINGS) {
                super.onCreate(savedInstanceState)
                instance!!.startWriteSettingsActivity(this, TYPE_WRITE_SETTINGS)
            } else if (byteExtra == TYPE_DRAW_OVERLAYS) {
                super.onCreate(savedInstanceState)
                instance!!.startOverlayPermissionActivity(this, TYPE_DRAW_OVERLAYS)
            }
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String?>,
            grantResults: IntArray
        ) {
            instance!!.onRequestPermissionsResult(this)
            finish()
        }

        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
            finish()
            return true
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == TYPE_WRITE_SETTINGS) {
                if (sSimpleCallback4WriteSettings == null) return
                if (instance!!.isGrantedWriteSettings) {
                    sSimpleCallback4WriteSettings!!.onGranted()
                } else {
                    sSimpleCallback4WriteSettings!!.onDenied()
                }
                sSimpleCallback4WriteSettings = null
            } else if (requestCode == TYPE_DRAW_OVERLAYS) {
                if (sSimpleCallback4DrawOverlays == null) return
                if (instance!!.isGrantedDrawOverlays) {
                    sSimpleCallback4DrawOverlays!!.onGranted()
                } else {
                    sSimpleCallback4DrawOverlays!!.onDenied()
                }
                sSimpleCallback4DrawOverlays = null
            }
            finish()
        }

        companion object {
            private const val TYPE = "TYPE"
            const val TYPE_RUNTIME: Int = 0x01
            const val TYPE_WRITE_SETTINGS: Int = 0x02
            const val TYPE_DRAW_OVERLAYS: Int = 0x03

            fun start(context: Context, type: Int) {
                val starter = Intent(context, PermissionActivity::class.java)
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                starter.putExtra(TYPE, type)
                context.startActivity(starter)
            }
        }
    }

    interface OnRationaleListener {
        fun rationale(shouldRequest: ShouldRequest?)

        interface ShouldRequest {
            fun again(again: Boolean)
        }
    }

    interface SimpleCallback {
        fun onGranted()

        fun onDenied()
    }

    interface FullCallback {
        fun onGranted(permissionsGranted: MutableList<String?>?)

        fun onDenied(
            permissionsDeniedForever: MutableList<String?>?,
            permissionsDenied: MutableList<String?>?
        )
    }

    interface ThemeCallback {
        fun onActivityCreate(activity: Activity?)
    }

    companion object {
        private var PERMISSIONS: MutableList<String?>? = null

        var instance: XPermission? = null
            private set
        private var sSimpleCallback4WriteSettings: SimpleCallback? = null
        private var sSimpleCallback4DrawOverlays: SimpleCallback? = null

        /**
         * Set the permissions.
         *
         * @param permissions The permissions.
         * @return the single [XPermission] instance
         */
        fun create(
            context: Context?,
            @PermissionConstants.PermissionGroup vararg permissions: String?
        ): XPermission? {
            val permission = instance ?: return XPermission(context, *permissions)
            permission.context = context?.applicationContext
            permission.prepare(*permissions)
            return permission
        }
    }
}
