package com.wkq.dev.feature.app.catalog

import org.junit.Assert.*
import org.junit.Test

/** 验证顶层信息架构：完整功能库、不重叠基础体系和稳定的常用入口。 */
class DirectoryPolicyTest {
    /** 功能库包含 Google 与统一文档库，且每个保留模块只出现一次。 */
    @Test fun functionalLibrariesAreCompleteAndUnique() {
        val modules = DirectoryPolicy.entries(false).map { it.module }
        assertEquals(10, modules.size); assertEquals(modules.size, modules.toSet().size)
        assertTrue("core_google" in modules); assertTrue("core_document" in modules)
        assertFalse("core_pdf" in modules); assertFalse("lib_document" in modules)
    }
    /** 八个基础模块归为五套体系，路由子模块不重复占用顶层入口。 */
    @Test fun foundationSystemsGroupRoutingWithoutLosingModules() {
        val entries = DirectoryPolicy.entries(true)
        assertEquals(5, entries.size)
        assertEquals(8, DemoCatalog.entries.count { it.category == "foundation" })
        assertTrue(entries.map { it.module }.containsAll(listOf("core_base", "core_utils", "core_network", "core_router_api", "lib_xpopup")))
        DirectoryPolicy.routerModules.forEach { assertNotNull(DirectoryPolicy.find(it)) }
        assertTrue(entries.map { it.module }.intersect(DirectoryPolicy.entries(false).map { it.module }.toSet()).isEmpty())
    }
    /** 收藏与最近相同路由只出现一次，旧路由及跨页入口不能泄露进快捷区。 */
    @Test fun shortcutsDeduplicateAndRespectPageBoundary() {
        val google = DirectoryPolicy.find("core_google")!!; val base = DirectoryPolicy.find("core_base")!!
        val rows = DirectoryPolicy.shortcuts(false, setOf(google.route, base.route), listOf(google.route, "removed", base.route))
        assertEquals(listOf(google), rows)
    }
    /** 搜索路由处理器能够找到路由体系，搜索与分类一起生效。 */
    @Test fun routingSearchAndCategoryFilterAreConsistent() {
        assertEquals(listOf("core_router_api"), DirectoryPolicy.filter(true, "", "processor") { "" }.map { it.module })
        assertTrue(DirectoryPolicy.filter(false, "image", "core_google") { "" }.isEmpty())
        assertEquals(listOf("core_google"), DirectoryPolicy.filter(false, "google", "") { "" }.map { it.module })
    }
}
