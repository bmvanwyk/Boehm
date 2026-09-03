package io.boehm.catalog

data class Catalog(
    val version: Int,
    val tools: Map<String, ToolDef>
)

data class ToolDef(
    val name: String,
    val description: String,
    val install: String?,
    val run: RunDef,
    val profiles: Map<String, ProfileDef>,
    val types: List<String> = listOf("http"),
    val toolVersions: List<String> = listOf("0.x")
)

data class RunDef(
    val command: String
)

data class ProfileDef(
    val name: String,
    val description: String?,
    val config: String?,
    val output: OutputDef,
    val overrides: Map<String, OverrideDef>
)

data class OutputDef(
    val path: String,
    val format: String,
    val schema: String
)

data class OverrideDef(
    val path: String?,
    val default: Any?
)
