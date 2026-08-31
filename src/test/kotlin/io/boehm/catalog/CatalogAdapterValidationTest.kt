package io.boehm.catalog

import io.boehm.model.TestPlan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogAdapterValidationTest {

    private fun adapter() = CatalogAdapter(
        toolDef = ToolDef(
            name = "tulip",
            description = "test",
            install = null,
            run = RunDef(command = "echo"),
            profiles = mapOf(
                "http-get" to ProfileDef(
                    name = "http-get",
                    description = null,
                    config = null,
                    output = OutputDef(path = "stdout", format = "text", schema = "none"),
                    overrides = mapOf(
                        "target_url" to OverrideDef(path = null, default = "https://example.com"),
                        "duration_sec" to OverrideDef(path = null, default = 30),
                        "warmup_sec" to OverrideDef(path = null, default = 5)
                    )
                )
            )
        ),
        profileName = "http-get"
    )

    @Test
    fun `validate rejects timeout shorter than duration plus warmup plus slack`() {
        val errors = adapter().validate(
            TestPlan(type = "http", profile = "http-get", targetUrl = "https://example.com",
                durationSec = 120, warmupSec = 10, timeoutSec = 60)
        )
        assertTrue(errors.any { it.field == "timeoutSec" }, "expected timeoutSec error, got: $errors")
    }

    @Test
    fun `validate accepts adequate timeout`() {
        val errors = adapter().validate(
            TestPlan(type = "http", profile = "http-get", targetUrl = "https://example.com",
                durationSec = 30, warmupSec = 5, timeoutSec = 60)
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `sanitize accepts target_url with query string`() {
        val adapter = adapter()
        val plan = TestPlan(type = "http", profile = "http-get", targetUrl = "https://example.com/api?foo=bar&baz=1%20x#frag",
            durationSec = 30, warmupSec = 5, timeoutSec = 60)
        // Should not throw IllegalArgumentException about target_url illegal characters
        try {
            adapter.run(plan)
        } catch (e: IllegalArgumentException) {
            if (e.message!!.contains("target_url") && e.message!!.contains("illegal characters")) {
                assertTrue(false, "query-string URL should be accepted, got: ${e.message}")
            }
            // other IllegalArgumentException (e.g., numeric) is not relevant
        } catch (e: Exception) {
            // parse errors etc. are expected for echo command, ignore
        }
        // Also validate should be clean
        val errors = adapter.validate(plan)
        assertTrue(errors.isEmpty(), "validate should be clean for query-string URL, got: $errors")
    }

    @Test
    fun `sanitize rejects URL with whitespace and shell metachars`() {
        val malicious = listOf(
            "https://example.com/api?foo=bar; rm -rf /",
            "https://example.com/api?foo=bar | cat /etc/passwd",
            "https://example.com/api with space",
            "https://example.com/api`id`",
            "https://example.com/api\$(whoami)"
        )
        for (url in malicious) {
            val plan = TestPlan(type = "http", profile = "http-get", targetUrl = url, durationSec = 30, warmupSec = 5, timeoutSec = 60)
            var threw = false
            try {
                // trigger sanitize via resolveOverrides path (used in run)
                val adapter = adapter()
                // run will call sanitize and throw
                adapter.run(plan)
            } catch (e: IllegalArgumentException) {
                threw = true
                assertTrue(e.message!!.contains("target_url") || e.message!!.contains("shell") || e.message!!.contains("whitespace"),
                    "expected sanitization error for '$url', got: ${e.message}")
            }
            assertTrue(threw, "expected IllegalArgumentException for malicious url: $url")
        }
    }
}
