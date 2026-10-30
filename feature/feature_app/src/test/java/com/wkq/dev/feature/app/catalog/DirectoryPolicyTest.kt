package com.wkq.dev.feature.app.catalog

import org.junit.Assert.*
import org.junit.Test

/** 验证三类库的互斥边界、迁移后的源码位置和独立快捷入口。 */
class DirectoryPolicyTest {
    /** 功能目录保留九个库，Google 独立归类，移出的旧模块不回流。 */
    @Test fun functionalLibrariesAreCompleteAndUnique() {
        val modules = DirectoryPolicy.entries(LibrarySection.FUNCTIONAL).map { it.module }
        assertEquals(9, modules.size); assertEquals(modules.size, modules.toSet().size)
        assertFalse("core_google" in modules); assertTrue("core_document" in modules)
        assertFalse("core_pdf" in modules); assertFalse("lib_document" in modules)
    }
    /** 八个基础模块归为五套体系，路由子模块不重复占用顶层入口。 */
    @Test fun foundationSystemsGroupRoutingWithoutLosingModules() {
        val entries = DirectoryPolicy.entries(LibrarySection.ARCHITECTURE)
        assertEquals(5, entries.size)
        assertEquals(8, DemoCatalog.entries.count { it.category == "foundation" })
        assertTrue(entries.map { it.module }.containsAll(listOf("core_base", "core_utils", "core_network", "core_router_api", "lib_xpopup")))
        DirectoryPolicy.routerModules.forEach { assertNotNull(DirectoryPolicy.find(it)) }
        assertTrue(entries.map { it.module }.intersect(DirectoryPolicy.entries(LibrarySection.FUNCTIONAL).map { it.module }.toSet()).isEmpty())
    }
    /** 收藏与最近相同路由只出现一次，旧路由及跨页入口不能泄露进快捷区。 */
    @Test fun shortcutsDeduplicateAndRespectPageBoundary() {
        val google = DirectoryPolicy.find("core_google")!!; val base = DirectoryPolicy.find("core_base")!!
        val rows = DirectoryPolicy.shortcuts(LibrarySection.THIRDPARTY, setOf(google.route, base.route), listOf(google.route, "removed", base.route))
        assertEquals(listOf(google), rows)
        assertTrue(DirectoryPolicy.shortcuts(LibrarySection.FUNCTIONAL, setOf(google.route, base.route), listOf(google.route, base.route)).isEmpty())
    }
    /** 搜索路由处理器能够找到路由体系，搜索与分类一起生效。 */
    @Test fun routingSearchAndCategoryFilterAreConsistent() {
        assertEquals(listOf("core_router_api"), DirectoryPolicy.filter(LibrarySection.ARCHITECTURE, "", "processor") { "" }.map { it.module })
        assertTrue(DirectoryPolicy.filter(LibrarySection.FUNCTIONAL, "image", "core_google") { "" }.isEmpty())
        assertTrue(DirectoryPolicy.filter(LibrarySection.FUNCTIONAL, "google", "") { "" }.isEmpty())
        assertEquals(listOf("core_google"), DirectoryPolicy.filter(LibrarySection.THIRDPARTY, "google", "") { "" }.map { it.module })
    }

    /** 三类入口不重叠且覆盖公共模块，内部支撑不占业务入口。 */
    @Test fun sectionsAreDisjointAndCoverEveryPublicLibrary() {
        val entries = LibrarySection.entries.flatMap { DirectoryPolicy.entries(it) }
        assertEquals(15, entries.size)
        assertEquals(entries.size, entries.map { it.module }.toSet().size)
        assertEquals(listOf("core_google"), DirectoryPolicy.entries(LibrarySection.THIRDPARTY).map { it.module })
        assertNull(DirectoryPolicy.find("lib_operations"))
        assertEquals(DemoCatalog.entries.map { it.module }.toSet(), (entries.map { it.module } + DirectoryPolicy.routerModules).toSet())
    }

    /** 稳定导航键兼容旧状态，源码位置与模块名分开，内部分类不接受为页面参数。 */
    @Test fun restoredKeysAndSourceLocationsRemainCompatible() {
        assertEquals(LibrarySection.ARCHITECTURE, LibrarySection.fromKey("foundation"))
        assertEquals(LibrarySection.FUNCTIONAL, LibrarySection.fromKey("libraries"))
        assertNull(LibrarySection.fromKey("internal"))
        assertNull(LibrarySection.fromKey(null))
        assertEquals("core/architecture/core_router_api", DirectoryPolicy.find("core_router_api")!!.sourcePath)
        assertEquals("core/functional/core_document", DirectoryPolicy.find("core_document")!!.sourcePath)
        assertEquals("core/thirdparty/core_google", DirectoryPolicy.find("core_google")!!.sourcePath)
    }
}
