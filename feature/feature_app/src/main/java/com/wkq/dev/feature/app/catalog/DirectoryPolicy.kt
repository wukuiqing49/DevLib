package com.wkq.dev.feature.app.catalog

/** 架构、功能与三方服务互斥分组；内部支撑不展示，路由子模块归入体系详情。 */
object DirectoryPolicy {
    val routerModules = listOf("core_router_api", "core_router_annotation", "core_router_processor", "core_router_gradle_plugin")
    /** 提供顶层库入口；路由注解、处理器和插件保留在体系详情中。 */
    fun entries(section: LibrarySection): List<DemoEntry> = DemoCatalog.entries.filter {
        it.section == section && (section != LibrarySection.ARCHITECTURE || it.module !in routerModules.drop(1))
    }
    /** 根据模块名查找原始登记项，详情与路由跳转始终使用原目录。 */
    fun find(module: String): DemoEntry? = DemoCatalog.entries.find { it.module == module }
    /** 常用优先并合并最近记录，保序去重且只返回当前页合法入口。 */
    fun shortcuts(section: LibrarySection, favorites: Set<String>, recent: List<String>): List<DemoEntry> {
        val available = entries(section)
        return (available.filter { it.route in favorites } + recent.mapNotNull { route -> available.find { it.route == route } })
            .distinctBy { it.route }.take(6)
    }
    /** 按分类和本地化文本筛选；路由子模块名称同样能找到路由体系。 */
    fun filter(section: LibrarySection, category: String, query: String, text: (DemoEntry) -> String): List<DemoEntry> =
        entries(section).filter { entry ->
            (category.isBlank() || entry.category == category) &&
                (query.isBlank() || entry.module.contains(query, true) || text(entry).contains(query, true) ||
                    (entry.module == "core_router_api" && routerModules.any { it.contains(query, true) }))
        }
}
