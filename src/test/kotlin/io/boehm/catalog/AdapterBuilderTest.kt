package io.boehm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdapterBuilderTest {

    private fun catalogWithSchemas(vararg schemas: String): Catalog {
        val profiles = schemas.associate { schema ->
            "$schema-profile" to ProfileDef(
                name = "$schema-profile",
                description = null,
                config = null,
                output = OutputDef(path = "stdout", format = "json", schema = schema),
                overrides = emptyMap()
            )
        }
        return Catalog(
            version = 1,
            tools = mapOf("tooly" to ToolDef("tooly", "d", null, RunDef("echo"), profiles))
        )
    }

    @Test
    fun `buildAdapters skips profiles whose schema has no parser`() {
        val parsers = mapOf<String, (String) -> io.boehm.model.RunResult>(
            "known-schema" to { _ -> throw UnsupportedOperationException() }
        )
        val adapters = buildAdapters(catalogWithSchemas("known-schema", "wrk-text"), System.getProperty("java.io.tmpdir"), parsers)

        assertEquals(listOf("tooly:known-schema-profile"), adapters.map { "${it.name}:${it.profile}" })
    }
}
