# Gson 解码 ID token 的展示字段，不能用于身份验证。
-keep class com.wkq.google.model.GoogleIdTokenClaims { *; }
-keepattributes Signature,*Annotation*

# Base 反射解析页面 ViewBinding 和 ViewModel，保留本库必要入口。
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class com.wkq.google.databinding.** { public static *** inflate(...); }
-keep class com.wkq.google.billing.ui.GoogleBillingViewModel { public <init>(); }
