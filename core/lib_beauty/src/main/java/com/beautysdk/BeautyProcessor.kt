package com.beautysdk

/** 帧处理契约：同线程启停与处理；具体实现声明输出所有权。实际美颜请使用 BeautySession。 */
interface FrameProcessor : AutoCloseable {
    fun start()
    fun process(frame: VideoFrame): VideoFrame
    fun stop()
    override fun close()
}

/**
 * 工程初始化用的旁路处理器，不包含美颜、人脸检测或 GL 渲染。
 * start/process/stop/close 由同一线程调用；start 后允许重复 start，stop 后可重启。
 * close 幂等且不可恢复；未 start 或已关闭时 process 抛出 IllegalStateException。
 * 不持有 Context，不申请权限，不创建线程、不复制或释放帧。
 */
class BeautyProcessor : FrameProcessor {
    private enum class State { IDLE, RUNNING, CLOSED }
    private var state = State.IDLE
    private var owner: Thread? = null
    private var lastTimestampNs = -1L

    @Synchronized
    override fun start() {
        checkThread()
        check(state != State.CLOSED) { "Processor is closed" }
        state = State.RUNNING
    }

    @Synchronized
    override fun process(frame: VideoFrame): VideoFrame {
        checkThread()
        check(state == State.RUNNING) { "Call start before processing frames" }
        require(frame.info.timestampNs > lastTimestampNs) { "Timestamps must strictly increase within a session" }
        lastTimestampNs = frame.info.timestampNs
        return frame
    }

    @Synchronized
    override fun stop() {
        checkThread()
        check(state != State.CLOSED) { "Processor is closed" }
        state = State.IDLE
        lastTimestampNs = -1L
    }

    @Synchronized
    override fun close() {
        checkThread()
        state = State.CLOSED
        lastTimestampNs = -1L
    }

    private fun checkThread() {
        val current = Thread.currentThread()
        if (owner == null) owner = current
        check(owner === current) { "Processor lifecycle and frames must use one thread" }
    }

    @Synchronized
    internal fun requireRunning() {
        checkThread()
        check(state == State.RUNNING) { "Call start before processing frames" }
    }
}
