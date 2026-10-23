# Gson 解码 ID token 的展示字段，不能用于身份验证。
-keep class com.wkq.google.model.GoogleIdTokenClaims { *; }
-keepattributes Signature,*Annotation*
