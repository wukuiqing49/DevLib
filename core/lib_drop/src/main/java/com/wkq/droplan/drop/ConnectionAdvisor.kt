package com.wkq.droplan.drop

data class ConnectionCandidate(
    val id: String, val reachable: Boolean, val offline: Boolean = true,
    val preparationSeconds: Double, val bytesPerSecond: Double?,
    val measurement: Measurement = Measurement.UNKNOWN,
)
enum class Measurement { UNKNOWN, HISTORICAL, CURRENT }
data class ConnectionAdvice(val candidate: ConnectionCandidate, val estimatedSeconds: Double?, val compared: Boolean)

/** 以总时间比较可达路径；未知速度不伪造速率，也不按接收端系统固定排序。 */
object ConnectionAdvisor {
    fun recommend(candidates: List<ConnectionCandidate>, remainingBytes: Long?): ConnectionAdvice? {
        require(remainingBytes == null || remainingBytes >= 0)
        val valid = candidates.filter { it.offline && it.reachable && it.preparationSeconds.isFinite() && it.preparationSeconds >= 0 }
        fun estimate(c: ConnectionCandidate): Double? = c.bytesPerSecond?.takeIf { it.isFinite() && it > 0 }
            ?.let { speed -> remainingBytes?.let { c.preparationSeconds + it.toDouble() / speed } }?.takeIf { it.isFinite() }
        val selected = valid.filter { estimate(it) != null }.minByOrNull { estimate(it)!! }
            ?: valid.filter { it.bytesPerSecond?.let { speed -> speed.isFinite() && speed > 0 } == true }
                .maxByOrNull { it.bytesPerSecond!! }
            ?: valid.minByOrNull { it.preparationSeconds } ?: return null
        val measured = valid.filter { it.measurement == Measurement.CURRENT && it.bytesPerSecond?.let { speed -> speed.isFinite() && speed > 0 } == true }
        // 不把重复 ID 或未测量的其他路径计作横向比较。
        return ConnectionAdvice(selected, estimate(selected), measured.distinctBy { it.id }.size > 1 && selected.measurement == Measurement.CURRENT)
    }

    fun canSwitch(activeDownloads: Int, authorizedReceivers: Int, reconnectAccepted: Boolean): Boolean =
        activeDownloads == 0 && (authorizedReceivers == 0 || reconnectAccepted)
}
