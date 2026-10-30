package com.lxj.xpopup.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.FloatRange
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.lxj.xpopup.R
import com.lxj.xpopup.core.AttachPopupView
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.core.BottomPopupView
import com.lxj.xpopup.core.BubbleAttachPopupView
import com.lxj.xpopup.core.CenterPopupView
import com.lxj.xpopup.core.DrawerPopupView
import com.lxj.xpopup.core.PositionPopupView
import com.lxj.xpopup.impl.FullScreenPopupView
import com.lxj.xpopup.impl.PartShadowPopupView
import com.lxj.xpopup.interfaces.XPopupImageLoader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Description:
 * Create by lxj, at 2018/12/7
 */
object XPopupUtils {
    //应用界面可见高度，可能不包含导航和状态栏，看Rom实现
    @JvmStatic
    fun getAppHeight(context: Context): Int {
        val activity = context2Activity(context)
        if (activity != null && activity.getWindow().getDecorView().getHeight() > 0) {
            return activity.getWindow().getDecorView().getHeight()
        }
        return getWindowBounds(context, false).height()
    }

    @JvmStatic
    fun getAppWidth(context: Context): Int {
        val activity = context2Activity(context)
        if (activity != null && activity.getWindow().getDecorView().getWidth() > 0) {
            return activity.getWindow().getDecorView().getWidth()
        }
        return getWindowBounds(context, false).width()
    }

    //屏幕的高度，包含状态栏，导航栏，看Rom实现
    @JvmStatic
    fun getScreenHeight(context: Context): Int {
        return getWindowBounds(context, true).height()
    }

    @JvmStatic
    fun getScreenWidth(context: Context): Int {
        return getWindowBounds(context, true).width()
    }

    private fun getWindowBounds(context: Context, maximum: Boolean): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            return Rect(
                if (maximum) wm.getMaximumWindowMetrics()
                    .getBounds() else wm.getCurrentWindowMetrics().getBounds()
            )
        }
        val metrics = context.getResources().getDisplayMetrics()
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    @JvmStatic
    fun dp2px(context: Context, dipValue: Float): Int {
        val scale = context.getResources().getDisplayMetrics().density
        return (dipValue * scale + 0.5f).toInt()
    }

    @JvmStatic
    fun getStatusBarHeight(window: Window?): Int {
        if (window != null) {
            val windowInsets = ViewCompat.getRootWindowInsets(window.getDecorView())
            if (windowInsets != null) {
                return windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            }
        }
        // Insets are window-specific and can change during multi-window or navigation-mode changes.
        return 0
    }

    /**
     * Return the navigation bar's height.
     *
     * @return the navigation bar's height
     */
    @JvmStatic
    fun getNavBarHeight(window: Window?): Int {
        if (window != null) {
            val windowInsets = ViewCompat.getRootWindowInsets(window.getDecorView())
            if (windowInsets != null) {
                if (!windowInsets.isVisible(WindowInsetsCompat.Type.navigationBars())) return 0
                val bars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
                return max(bars.bottom, max(bars.left, bars.right))
            }
        }
        return 0
    }

    @JvmStatic
    fun getActionBarHeight(context: Context?): Int {
        val activity = context2Activity(context)
        if (activity == null) return 0
        if (activity is AppCompatActivity) {
            val supportActionBar = activity.getSupportActionBar()
            return if (supportActionBar == null) 0 else supportActionBar.getHeight()
        }
        val actionBar = activity.getActionBar()
        return if (actionBar == null) 0 else actionBar.getHeight()
    }

    @JvmStatic
    fun setWidthHeight(target: View, width: Int, height: Int) {
        if (width <= 0 && height <= 0) return
        val params = target.getLayoutParams()
        if (width > 0) params.width = width
        if (height > 0) params.height = height
        target.setLayoutParams(params)
    }

    @JvmStatic
    fun applyPopupSize(
        content: ViewGroup, maxWidth: Int, maxHeight: Int,
        popupWidth: Int, popupHeight: Int, afterApplySize: Runnable?
    ) {
        content.post(Runnable {
            val params = content.getLayoutParams()
            val implView = content.getChildAt(0) ?: return@Runnable
            val implParams = implView.getLayoutParams()
            // 假设默认Content宽是match，高是wrap
            val w = content.getMeasuredWidth()
            // response impl view wrap_content params.
            if (maxWidth > 0) {
                //指定了最大宽度，就限制最大宽度
                if (w > maxWidth) params.width = min(w, maxWidth)
                if (implParams.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                    implParams.width = min(w, maxWidth)
                    if (implParams is MarginLayoutParams) {
                        val mp = implParams
                        implParams.width = implParams.width - mp.leftMargin - mp.rightMargin
                    }
                }
                if (popupWidth > 0) {
                    params.width = min(popupWidth, maxWidth)
                    implParams.width = min(popupWidth, maxWidth)
                }
            } else if (popupWidth > 0) {
                params.width = popupWidth
                implParams.width = popupWidth
            }

            if (maxHeight > 0) {
                val h = content.getMeasuredHeight()
                if (h > maxHeight) params.height = min(h, maxHeight)
                if (popupHeight > 0) {
                    params.height = min(popupHeight, maxHeight)
                    implParams.height = min(popupHeight, maxHeight)
                }
            } else if (popupHeight > 0) {
                params.height = popupHeight
                implParams.height = popupHeight
            } else {
//                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
//                implParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            }

            implView.setLayoutParams(implParams)
            content.setLayoutParams(params)
            content.post(Runnable {
                if (afterApplySize != null) {
                    afterApplySize.run()
                }
            })
        })
    }

    @JvmStatic
    fun setCursorDrawableColor(et: EditText?, color: Int) {
        //暂时没有找到有效的方法来动态设置cursor的颜色
    }

    @JvmStatic
    fun createBitmapDrawable(context: Context, width: Int, color: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(width, dp2px(context, 1.5f), Bitmap.Config.ARGB_4444)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.setColor(color)
        canvas.drawRect(0f, 0f, bitmap.getWidth().toFloat(), bitmap.getHeight().toFloat(), paint)
        val bitmapDrawable = BitmapDrawable(context.getResources(), bitmap)
        bitmapDrawable.setGravity(Gravity.BOTTOM)
        return bitmapDrawable
    }

    @JvmStatic
    fun createSelector(defaultDrawable: Drawable?, focusDrawable: Drawable?): StateListDrawable {
        val stateListDrawable = StateListDrawable()
        stateListDrawable.addState(intArrayOf(android.R.attr.state_focused), focusDrawable)
        stateListDrawable.addState(intArrayOf(), defaultDrawable)
        return stateListDrawable
    }

    @JvmStatic
    fun isInRect(x: Float, y: Float, rect: Rect): Boolean {
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
    }

    private var sDecorViewDelta = 0

    @JvmStatic
    fun getDecorViewInvisibleHeight(window: Window?): Int {
        if (window == null) return 0
        val decorView = window.getDecorView()
        if (decorView == null) return 0
        val windowInsets = ViewCompat.getRootWindowInsets(decorView)
        if (windowInsets != null) {
            return if (windowInsets.isVisible(WindowInsetsCompat.Type.ime()))
                windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            else
                0
        }
        val outRect = Rect()
        decorView.getWindowVisibleDisplayFrame(outRect)
        val delta: Int = abs(decorView.getBottom() - outRect.bottom)
        if (delta <= getNavBarHeight(window)) {
            sDecorViewDelta = delta
            return 0
        }
        return delta - sDecorViewDelta
    }

    //监听到的keyboardHeight有一定几率是错误的，比如在同时显示导航栏和弹出输入法的时候，有一定几率会算上导航栏的高度，
    //这个不是必现的，暂时无解
    private var preKeyboardHeight = 0

    @JvmStatic
    fun moveUpToKeyboard(keyboardHeight: Int, pv: BasePopupView) {
        preKeyboardHeight = keyboardHeight
        pv.post(object : Runnable {
            override fun run() {
                moveUpToKeyboardInternal(preKeyboardHeight, pv)
            }
        })
    }

    private fun moveUpToKeyboardInternal(keyboardHeight: Int, pv: BasePopupView) {
        if (pv.popupInfo == null || !pv.popupInfo.isMoveUpToKeyboard) return
        //暂时忽略PartShadow弹窗和AttachPopupView
        if (pv is PositionPopupView || pv is AttachPopupView || pv is BubbleAttachPopupView) {
            return
        }
        //判断是否盖住输入框
        val allEts = ArrayList<EditText>()
        findAllEditText(allEts, pv)
        var focusEt: EditText? = null
        for (et in allEts) {
            if (et.isFocused()) {
                focusEt = et
                break
            }
        }

        var dy = 0
        var popupHeight = pv.popupContentView.getHeight()
        var popupWidth = pv.popupContentView.getWidth()
        if (pv.popupImplView != null) {
            popupHeight = min(popupHeight, pv.popupImplView.getMeasuredHeight())
            popupWidth = min(popupWidth, pv.popupImplView.getMeasuredWidth())
        }

        val screenHeight = pv.getMeasuredHeight()
        var focusEtTop = 0
        var focusBottom = 0
        if (focusEt != null) {
            val locations = IntArray(2)
            focusEt.getLocationInWindow(locations)
            focusEtTop = locations[1]
            focusBottom = focusEtTop + focusEt.getMeasuredHeight()
        }
        //执行上移的逻辑
        if (pv is FullScreenPopupView || pv is DrawerPopupView) {
            val overflowHeight = (((focusBottom + keyboardHeight) - screenHeight
                    - pv.popupContentView.getTranslationY())).toInt()
            if (focusEt != null && overflowHeight > 0) {
                dy = overflowHeight
            }
        } else if (pv is CenterPopupView) {
            val popupBottom = (screenHeight + popupHeight) / 2
            val targetY = popupBottom + keyboardHeight - screenHeight
            if (focusEt != null && focusEtTop - targetY < 0) {
//                targetY += focusEtTop - targetY /*- statusBarHeight*/;//限制不能被状态栏遮住
            }
            dy = max(0, targetY)
        } else if (pv is BottomPopupView) {
            dy = keyboardHeight
        } else if (pv is PartShadowPopupView) {
            val overflowHeight = (((focusBottom + keyboardHeight) - screenHeight
                    - pv.popupContentView.getTranslationY())).toInt()
            if (focusEt != null && overflowHeight > 0) {
                dy = overflowHeight
            }
        }
        val animDuration = 180
        pv.popupContentView.animate().translationY(-dy.toFloat())
            .setDuration(animDuration.toLong())
            .setInterpolator(LinearOutSlowInInterpolator())
            .start()
    }

    @JvmStatic
    fun moveDown(pv: BasePopupView) {
        //暂时忽略PartShadow弹窗和AttachPopupView
        if (pv is PositionPopupView || pv is AttachPopupView || pv is BubbleAttachPopupView) return
        if (pv is FullScreenPopupView && pv.popupContentView.hasTransientState()) {
            //如果正在执行动画，则不下移
            return
        }
        pv.popupContentView.animate().translationY(0f)
            .setInterpolator(LinearInterpolator())
            .setDuration(100).start()
    }

    @JvmStatic
    fun isNavBarVisible(window: Window?): Boolean {
        if (window == null) return false
        val decor = window.decorView
        val rootInsets = ViewCompat.getRootWindowInsets(decor)
        if (rootInsets != null) {
            return rootInsets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
        return false
            // 对于三星手机，android10以下非OneUI2的版本，比如 s8，note8 等设备上，
            // 导航栏显示存在bug："当用户隐藏导航栏时显示输入法的时候导航栏会跟随显示"，会导致隐藏输入法之后判断错误
            // 这个问题在 OneUI 2 & android 10 版本已修复
    }

    @JvmStatic
    fun findAllEditText(list: ArrayList<EditText>, group: ViewGroup) {
        for (i in 0 until group.getChildCount()) {
            val v = group.getChildAt(i)
            if (v is EditText && v.getVisibility() == View.VISIBLE) {
                list.add(v)
            } else if (v is ViewGroup) {
                findAllEditText(list, v)
            }
        }
    }

    @JvmStatic
    fun saveBmpToAlbum(context: Context, imageLoader: XPopupImageLoader, uri: Any) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute(object : Runnable {
            override fun run() {
                val source = imageLoader.getImageFile(context, uri)
                if (source == null) {
                    showToast(context, context.getString(R.string.xpopup_image_not_exist))
                    return
                }
                try {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        context.getPackageName()
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val destFile = File(
                        dir,
                        System.currentTimeMillis().toString() + "." + getImageType(source)
                    )
                    if (Build.VERSION.SDK_INT < 29) {
                        if (destFile.exists()) destFile.delete()
                        destFile.createNewFile()
                        FileOutputStream(destFile).use { out ->
                            writeFileFromIS(out, FileInputStream(source))
                        }
                        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        intent.setData(Uri.parse("file://" + destFile.getAbsolutePath()))
                        context.sendBroadcast(intent)
                    } else {
                        //android10以上，增加了新字段，自己insert，因为RELATIVE_PATH，DATE_EXPIRES，IS_PENDING是29新增字段
                        val contentValues = ContentValues()
                        contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, destFile.getName())
                        contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/*")
                        val contentUri: Uri
                        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        } else {
                            contentUri = MediaStore.Images.Media.INTERNAL_CONTENT_URI
                        }
                        contentValues.put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_DCIM + "/" + context.getPackageName()
                        )
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
                        val uri = context.getContentResolver().insert(contentUri, contentValues)
                        if (uri == null) {
                            showToast(context, context.getString(R.string.xpopup_saved_fail))
                            return
                        }

                        val resolver = context.getContentResolver()
                        resolver.openOutputStream(uri).use { out ->
                            writeFileFromIS(out, FileInputStream(source))
                        }
                        // Everything went well above, publish it!
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        //                            contentValues.putNull(MediaStore.MediaColumns.DATE_EXPIRES);
                        resolver.update(uri, contentValues, null, null)
                    }
                    showToast(context, context.getString(R.string.xpopup_saved_to_gallery))
                } catch (e: Exception) {
                    e.printStackTrace()
                    showToast(context, context.getString(R.string.xpopup_saved_fail))
                }
            }
        })
    }

    private fun showToast(context: Context?, text: String?) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post(object : Runnable {
            override fun run() {
                if (context != null) {
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun writeFileFromIS(fos: OutputStream?, `is`: InputStream): Boolean {
        var os: OutputStream? = null
        try {
            os = BufferedOutputStream(fos)
            val data: ByteArray? = ByteArray(8192)
            var len: Int
            while ((`is`.read(data, 0, 8192).also { len = it }) != -1) {
                os.write(data, 0, len)
            }
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            try {
                `is`.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            try {
                if (os != null) {
                    os.close()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun renderScriptBlur(
        context: Context?, src: Bitmap?,
        @FloatRange(from = 0.0, to = 25.0, fromInclusive = false) radius: Float,
        recycle: Boolean
    ): Bitmap? {
        if (src == null) return null
        val config = if (src.getConfig() == null) Bitmap.Config.ARGB_8888 else src.getConfig()
        val result = if (recycle && src.isMutable()) src else src.copy(config!!, true)
        if (recycle && result != src && !src.isRecycled()) src.recycle()
        val width = result.getWidth()
        val height = result.getHeight()
        val blurRadius: Int = max(1, Math.round(radius))
        val source = IntArray(width * height)
        val horizontal = IntArray(source.size)
        result.getPixels(source, 0, width, 0, 0, width, height)
        boxBlurHorizontal(source, horizontal, width, height, blurRadius)
        boxBlurVertical(horizontal, source, width, height, blurRadius)
        result.setPixels(source, 0, width, 0, 0, width, height)
        return result
    }

    private fun boxBlurHorizontal(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val color = source[row + max(0, min(width - 1, i))]
                a += color ushr 24
                r += color shr 16 and 0xff
                g += color shr 8 and 0xff
                b += color and 0xff
            }
            for (x in 0 until width) {
                target[row + x] =
                    a / diameter shl 24 or (r / diameter shl 16) or (g / diameter shl 8) or b / diameter
                val remove = source[row + max(0, x - radius)]
                val add = source[row + min(width - 1, x + radius + 1)]
                a += (add ushr 24) - (remove ushr 24)
                r += (add shr 16 and 0xff) - (remove shr 16 and 0xff)
                g += (add shr 8 and 0xff) - (remove shr 8 and 0xff)
                b += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    private fun boxBlurVertical(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val diameter = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val color = source[max(0, min(height - 1, i)) * width + x]
                a += color ushr 24
                r += color shr 16 and 0xff
                g += color shr 8 and 0xff
                b += color and 0xff
            }
            for (y in 0 until height) {
                target[y * width + x] =
                    a / diameter shl 24 or (r / diameter shl 16) or (g / diameter shl 8) or b / diameter
                val remove = source[max(0, y - radius) * width + x]
                val add = source[min(height - 1, y + radius + 1) * width + x]
                a += (add ushr 24) - (remove ushr 24)
                r += (add shr 16 and 0xff) - (remove shr 16 and 0xff)
                g += (add shr 8 and 0xff) - (remove shr 8 and 0xff)
                b += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    /**
     * View to bitmap.
     *
     * @param view The view.
     * @return bitmap
     */
    @JvmOverloads
    fun view2Bitmap(view: View?, clipHeight: Int = -1, scale: Int = 1): Bitmap? {
        if (view == null) return null
        val drawingCacheEnabled = view.isDrawingCacheEnabled()
        val willNotCacheDrawing = view.willNotCacheDrawing()
        view.setDrawingCacheEnabled(true)
        view.setWillNotCacheDrawing(false)
        var drawingCache = view.getDrawingCache()
        val bitmap: Bitmap?
        if (null == drawingCache) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight())
            view.buildDrawingCache()
            drawingCache = view.getDrawingCache()
            if (drawingCache != null) {
                bitmap = Bitmap.createBitmap(
                    drawingCache,
                    0,
                    0,
                    drawingCache.getWidth(),
                    if (clipHeight > 0) clipHeight else drawingCache.getHeight()
                )
            } else {
                bitmap = Bitmap.createBitmap(
                    view.getMeasuredWidth(),
                    if (clipHeight > 0) clipHeight else view.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                view.draw(canvas)
            }
        } else {
            bitmap = Bitmap.createBitmap(
                drawingCache,
                0,
                0,
                drawingCache.getWidth(),
                if (clipHeight > 0) clipHeight else drawingCache.getHeight()
            )
        }
        view.destroyDrawingCache()
        view.setWillNotCacheDrawing(willNotCacheDrawing)
        view.setDrawingCacheEnabled(drawingCacheEnabled)
        val small = Bitmap.createScaledBitmap(
            bitmap,
            view.getMeasuredWidth() / scale,
            view.getMeasuredHeight() / scale,
            true
        )
        if (!bitmap.isRecycled() && bitmap != small) bitmap.recycle()
        return small
    }

    @JvmStatic
    fun isLayoutRtl(context: Context): Boolean {
        val primaryLocale: Locale?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            primaryLocale = context.getResources().getConfiguration().getLocales().get(0)
        } else {
            primaryLocale = context.getResources().getConfiguration().locale
        }
        return TextUtils.getLayoutDirectionFromLocale(primaryLocale) == View.LAYOUT_DIRECTION_RTL
    }

    @JvmStatic
    fun context2Activity(ctx: Context?): Activity? {
        var context = ctx
        while (context is ContextWrapper) {
            if (context is Activity) {
                return context
            } else {
                context = context.getBaseContext()
            }
        }
        return null
    }

    @JvmStatic
    fun context2Activity(view: View): Activity? {
        return context2Activity(view.getContext())
    }

    @JvmStatic
    fun createDrawable(color: Int, radius: Float): Drawable {
        val drawable = GradientDrawable()
        drawable.setShape(GradientDrawable.RECTANGLE)
        drawable.setColor(color)
        drawable.setCornerRadius(radius)
        return drawable
    }

    @JvmStatic
    fun createDrawable(
        color: Int, tlRadius: Float, trRadius: Float, brRadius: Float,
        blRadius: Float
    ): Drawable {
        val drawable = GradientDrawable()
        drawable.setShape(GradientDrawable.RECTANGLE)
        drawable.setColor(color)
        drawable.setCornerRadii(
            floatArrayOf(
                tlRadius, tlRadius,
                trRadius, trRadius,
                brRadius, brRadius,
                blRadius, blRadius
            )
        )
        return drawable
    }

    @JvmStatic
    fun hasSetKeyListener(view: View?): Boolean {
        try {
            val viewClazz = Class.forName("android.view.View")
            val listenerInfoMethod = viewClazz.getDeclaredMethod("getListenerInfo")
            if (!listenerInfoMethod.isAccessible()) {
                listenerInfoMethod.setAccessible(true)
            }
            val listenerInfoObj = listenerInfoMethod.invoke(view)
            val listenerInfoClazz = Class.forName("android.view.View\$ListenerInfo")
            val mOnKeyListenerField = listenerInfoClazz.getDeclaredField("mOnKeyListener")
            if (!mOnKeyListenerField.isAccessible()) {
                mOnKeyListenerField.setAccessible(true)
            }
            val keyListener = mOnKeyListenerField.get(listenerInfoObj)
            return keyListener != null
        } catch (e: Exception) {
            return false
        }
    }

    @JvmStatic
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        maxWidth: Int,
        maxHeight: Int
    ): Int {
        var height = options.outHeight
        var width = options.outWidth
        var inSampleSize = 1
        while (height > maxHeight || width > maxWidth) {
            height = height shr 1
            width = width shr 1
            inSampleSize = inSampleSize shl 1
        }
        return inSampleSize
    }

    @JvmStatic
    fun getBitmap(file: File?, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (file == null) return null
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.getAbsolutePath(), options)
        options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options)
    }

    @JvmStatic
    fun getImageSize(file: File?): IntArray {
        if (file == null) return intArrayOf(0, 0)
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts)
        return intArrayOf(opts.outWidth, opts.outHeight)
    }

    @JvmStatic
    fun getImageType(file: File?): String {
        if (file == null) return ""
        var `is`: InputStream? = null
        try {
            `is` = FileInputStream(file)
            val bytes = ByteArray(12)
            if (`is`.read(bytes) != -1) {
                val type = bytes2HexString(bytes, true).uppercase(Locale.getDefault())
                if (type.contains("FFD8FF")) {
                    return "jpg"
                } else if (type.contains("89504E47")) {
                    return "png"
                } else if (type.contains("47494638")) {
                    return "gif"
                } else if (type.contains("49492A00") || type.contains("4D4D002A")) {
                    return "tiff"
                } else if (type.contains("424D")) {
                    return "bmp"
                } else if (type.startsWith("52494646") && type.endsWith("57454250")) { //524946461c57000057454250-12个字节
                    return "webp"
                } else if (type.contains("00000100") || type.contains("00000200")) {
                    return "ico"
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                if (`is` != null) {
                    `is`.close()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return ""
    }

    private val HEX_DIGITS_UPPER =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
    private val HEX_DIGITS_LOWER =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    @JvmStatic
    fun bytes2HexString(bytes: ByteArray?, isUpperCase: Boolean): String {
        if (bytes == null) return ""
        val hexDigits = if (isUpperCase) HEX_DIGITS_UPPER else HEX_DIGITS_LOWER
        val len = bytes.size
        if (len <= 0) return ""
        val ret = CharArray(len shl 1)
        var i = 0
        var j = 0
        while (i < len) {
            ret[j++] = hexDigits[bytes[i].toInt() shr 4 and 0x0f]
            ret[j++] = hexDigits[bytes[i].toInt() and 0x0f]
            i++
        }
        return String(ret)
    }


    @JvmStatic
    fun getRotateDegree(filePath: String): Int {
        try {
            val exifInterface = ExifInterface(filePath)
            val orientation = exifInterface.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                return 90
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                return 180
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                return 270
            } else {
                return 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        }
    }

    @JvmStatic
    fun rotate(
        src: Bitmap,
        degrees: Int,
        px: Float,
        py: Float
    ): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix()
        matrix.setRotate(degrees.toFloat(), px, py)
        val ret = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true)
        return ret
    }

    @JvmStatic
    fun getViewRect(view: View): Rect {
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        return rect
    }

    @JvmStatic
    fun isLandscape(context: Context): Boolean {
        return (context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE)
    }

    val isTablet: Boolean
        get() = ((Resources.getSystem().getConfiguration().screenLayout
                and Configuration.SCREENLAYOUT_SIZE_MASK)
                >= Configuration.SCREENLAYOUT_SIZE_LARGE)

    @JvmStatic
    fun setVisible(view: View?, isVisible: Boolean) {
        if (view != null) {
            view.setVisibility(if (isVisible) View.VISIBLE else View.GONE)
        }
    }
}
