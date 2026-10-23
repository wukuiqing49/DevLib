package com.wkq.nas.transfer

/** 范围匹配随路径深度增长，不对每个扫描条目线性遍历全部选择。 */
internal class SyncSelection(private val selected: Set<String>) {
    private val ancestors = HashSet<String>()
    init {
        selected.forEach { path ->
            require(path.isNotEmpty() && TransferPaths.normalize(path) == path)
            var parent = TransferPaths.parent(path)
            while (parent.isNotEmpty() && ancestors.add(parent)) parent = TransferPaths.parent(parent)
        }
    }
    fun contains(path: String): Boolean {
        if (selected.isEmpty() || path in ancestors || path in selected) return true
        var parent = TransferPaths.parent(path)
        while (parent.isNotEmpty()) {
            if (parent in selected) return true
            parent = TransferPaths.parent(parent)
        }
        return false
    }
}
