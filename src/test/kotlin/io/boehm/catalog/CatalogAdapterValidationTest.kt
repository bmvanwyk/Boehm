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
}
