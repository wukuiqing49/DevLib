# core_util shared keep rules
# MMKV uses native/reflection entry points.
-keep class com.tencent.mmkv.** { *; }
-dontwarn com.tencent.mmkv.**

# Preserve Parcelable creators so parcelable objects remain compatible in release builds.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
