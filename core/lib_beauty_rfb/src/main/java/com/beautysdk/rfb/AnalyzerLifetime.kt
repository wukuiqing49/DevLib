package com.beautysdk.rfb

/** ONNX 会话由创建线程独占；提前拒绝跨线程调用与释放后的推理，关闭允许重复。 */
internal class AnalyzerLifetime {
    private val owner = Thread.currentThread()
    private var closed = false
    fun requireOpen() {
        checkThread()
        check(!closed) { "Analyzer is closed" }
    }
    fun beginClose(): Boolean {
        checkThread()
        if (closed) return false
        closed = true
        return true
    }
    private fun checkThread() {
        check(Thread.currentThread() === owner) { "Create, analyze and close on the same worker thread" }
    }
}
