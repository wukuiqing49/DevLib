package com.wkq.droplan.html

import org.junit.Assert.*
import org.junit.Test

class WebAssetCatalogTest {
    @Test fun bundledPageHasOfflineResourcesAndMatchingTranslations() {
        val root = java.io.File(requireNotNull(System.getProperty("droplan.web.root")))
        val html = java.io.File(root, "index.html").readText()
        val languages = listOf("zh", "en", "ja", "ko", "de", "fr", "es", "pt-BR")
        val references = Regex("(?:src|href)=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        for (path in references + languages.map { "/assets/$it.json" }) {
            val asset = WebAssetCatalog.resolve(path)
            assertNotNull(path, asset)
            assertTrue(path, java.io.File(root, asset!!.assetPath.removePrefix("droplan_web/")).length() > 0)
        }
        val zh = org.json.JSONObject(java.io.File(root, "zh.json").readText())
        val en = org.json.JSONObject(java.io.File(root, "en.json").readText())
        languages.forEach { tag ->
            val messages = org.json.JSONObject(java.io.File(root, "$tag.json").readText())
            assertEquals(tag, en.keys().asSequence().toSet(), messages.keys().asSequence().toSet())
            messages.keys().forEach { assertTrue("$tag/$it", messages.getString(it).isNotBlank()) }
        }
        Regex("data-i18n=\"([^\"]+)\"").findAll(html).forEach {
            assertTrue(it.groupValues[1], en.has(it.groupValues[1]) && zh.has(it.groupValues[1]))
        }
    }
    @Test fun allowsOnlyBundledResources() {
        assertEquals(WebAssetCatalog.resolve("/"), WebAssetCatalog.resolve("/index.html"))
        assertNotNull(WebAssetCatalog.resolve("/assets/app.css"))
        for (path in listOf("/../secret", "/%2e%2e/secret", "/assets/../../secret", "/index.html?x=1", "file:///secret")) {
            assertNull(path, WebAssetCatalog.resolve(path))
        }
    }
}
