# JNI 导出符号由完整类名和方法名组成，R8 不得改写这些入口。
-keep class com.phototoolkit.raw.RawNative { *; }
