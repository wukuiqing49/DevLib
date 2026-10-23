# WorkManager 通过反射恢复持久化任务，保留类名与构造函数。
-keep class com.wkq.nas.transfer.work.TransferQueueWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn org.bouncycastle.**
# EdDSA 的 JDK 专用兼容分支；Android 使用 EdDSAPublicKey，不使用 sun.security 类型。
-dontwarn sun.security.x509.X509Key

-keep class com.wkq.nas.transfer.work.V2Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
