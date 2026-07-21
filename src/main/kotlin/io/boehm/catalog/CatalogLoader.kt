package io.boehm.catalog

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

class CatalogLoader(private val catalogPath: String) {

    fun load(): Catalog {
        val raw = Yaml().load<Map<String, Any>>(FileInputStream(File(catalogPath)))
        return parseCatalog(raw)
    }

    fun loadTool(name: String): ToolDef? {
        return load().tools[name]
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCatalog(raw: Map<String, Any>): Catalog {
        val version = (raw["version"] as? Int) ?: 1
        val toolsRaw = raw["tools"] as? Map<String, Any> ?: emptyMap()
        val tools = toolsRaw.map { (name, def) ->
            val toolRaw = def as Map<String, Any>
            name to parseToolDef(name, toolRaw)
        }.toMap()
        return Catalog(version, tools)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolDef(name: String, raw: Map<String, Any>): ToolDef {
        val description = raw["description"] as? String ?: ""
        val install = raw["install"] as? String
        val runRaw = raw["run"] as? Map<String, Any> ?: emptyMap()
        val run = RunDef(command = runRaw["command"] as? String ?: "")
        val profilesRaw = raw["profiles"] as? Map<String, Any> ?: emptyMap()
        val profiles = profilesRaw.map { (pName, pDef) ->
            pName to parseProfileDef(pName, pDef as Map<String, Any>)
        }.toMap()
        return ToolDef(name, description, install, run, profiles)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseProfileDef(name: String, raw: Map<String, Any>): ProfileDef {
        val description = raw["description"] as? String
        val config = raw["config"] as? String
        val outputRaw = raw["output"] as? Map<String, Any> ?: emptyMap()
        val output = OutputDef(
            path = outputRaw["path"] as? String ?: "",
            format = outputRaw["format"] as? String ?: "json",
            schema = outputRaw["schema"] as? String ?: ""
        )
        val overridesRaw = raw["overrides"] as? Map<String, Any> ?: emptyMap()
        val overrides = overridesRaw.map { (oName, oDef) ->
            val oRaw = oDef as Map<String, Any>
            oName to OverrideDef(
                path = oRaw["path"] as? String,
                default = oRaw["default"]
            )
        }.toMap()
        return ProfileDef(name, description, config, output, overrides)
    }
}
