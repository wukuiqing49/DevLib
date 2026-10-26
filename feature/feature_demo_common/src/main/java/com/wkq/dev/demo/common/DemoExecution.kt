package com.wkq.dev.demo.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 在不可取消区完成历史收尾；普通持久化失败单独报告，任何退出路径均复位 UI，取消不转为成功。 */
internal suspend fun finishDemoOperation(
    persist: suspend () -> Unit,
    onFailure: (Exception) -> Unit,
    reset: () -> Unit,
) {
    withContext(NonCancellable) {
        try {
            persist()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(error)
        } finally {
            reset()
        }
    }
}
