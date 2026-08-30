package io.boehm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class CatalogLoaderTest {
    private val catalogPath = File("catalog.yaml").absolutePath

    @Test
    fun `load returns catalog with all tools`() {
        val catalog = CatalogLoader(catalogPath).load()
        assertTrue(catalog.tools.containsKey("tulip"))
        assertTrue(catalog.tools.containsKey("k6"))
        assertTrue(catalog.tools.containsKey("jmeter"))
        assertTrue(catalog.tools.containsKey("gatling"))
        assertEquals(4, catalog.tools.size)
        assertEquals(1, catalog.version)
    }

    @Test
    fun `loadTool returns correct tool definition`() {
        val tool = CatalogLoader(catalogPath).loadTool("tulip")
        assertNotNull(tool)
        assertEquals("tulip", tool!!.name)
        assertTrue(tool.description.isNotBlank())
        assertNotNull(tool.install)
    }

    @Test
    fun `loadTool returns null for unknown tool`() {
        val tool = CatalogLoader(catalogPath).loadTool("nonexistent")
        assertNull(tool)
    }

    @Test
    fun `tulip profiles include http-get and demo`() {
        val catalog = CatalogLoader(catalogPath).load()
        val tulip = catalog.tools["tulip"]!!
        assertTrue(tulip.profiles.containsKey("http-get"))
        assertTrue(tulip.profiles.containsKey("demo"))
    }

    @Test
    fun `tulip http-get profile has correct overrides`() {
        val catalog = CatalogLoader(catalogPath).load()
        val profile = catalog.tools["tulip"]!!.profiles["http-get"]!!
        assertEquals("profiles/tulip/http-get.jsonc", profile.config)
        assertTrue(profile.overrides.containsKey("target_url"))
        assertTrue(profile.overrides.containsKey("rate_per_sec"))
        assertTrue(profile.overrides.containsKey("duration_sec"))
        assertEquals("https://httpbin.org/get", profile.overrides["target_url"]!!.default)
    }

    @Test
    fun `k6 profile has no config and uses env vars`() {
        val catalog = CatalogLoader(catalogPath).load()
        val profile = catalog.tools["k6"]!!.profiles["http-get"]!!
        assertNotNull(profile.config)
        assertTrue(profile.config!!.endsWith(".js"))
        assertNull(profile.overrides["target_url"]!!.path)
    }

    @Test
    fun `gatling has scala config template`() {
        val catalog = CatalogLoader(catalogPath).load()
        assertTrue(catalog.tools["gatling"]!!.profiles["http-get"]!!.config!!.endsWith(".scala"))
    }
}
