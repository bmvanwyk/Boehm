package io.boehm.catalog

import io.boehm.adapters.PerfToolAdapter
import io.boehm.model.RunResult

/**
 * Builds one CatalogAdapter per catalog profile that has a parser for its output
 * schema. Profiles without a parser are skipped with a stderr note — they appear
 * nowhere in the server's tool surface rather than failing confusingly at run time.
 */
fun buildAdapters(
    catalog: Catalog,
    baseDir: String,
    parsers: Map<String, (String) -> RunResult>
): List<PerfToolAdapter> =
    catalog.tools.flatMap { (_, toolDef) ->
        toolDef.profiles.values.mapNotNull { profileDef ->
            if (profileDef.output.schema !in parsers) {
                System.err.println(
                    "boehm: skipping ${toolDef.name}:${profileDef.name} — no parser for schema '${profileDef.output.schema}'"
                )
                null
            } else {
                CatalogAdapter(toolDef, profileDef.name, baseDir, parsers)
            }
        }
    }
