package io.boehm.core

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

data class AdapterRow(val name: String, val supportedTypes: String, val version: String, val toolVersions: String)
data class ScenarioRow(val id: String, val tool: String, val name: String, val testPlan: String, val createdAt: String)
data class RunRow(val id: String, val scenarioId: String, val tool: String, val status: String,
                  val createdAt: String, val startedAt: String?, val completedAt: String?,
                  val error: String?, val summary: String?, val rawOutputPath: String?,
                  val metadata: String?)

class Store(private val dbPath: String) {
    private var _conn: Connection? = null
    private val conn: Connection
        get() {
            if (_conn == null) {
                Class.forName("org.sqlite.JDBC")
                val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
                c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                initSchema(c)
                _conn = c
            }
            return _conn!!
        }

    private fun initSchema(c: Connection) {
        c.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS adapters (
                    name TEXT PRIMARY KEY,
                    supported_types TEXT NOT NULL,
                    adapter_version TEXT NOT NULL,
                    tool_versions TEXT NOT NULL
                )
            """)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS test_scenarios (
                    id TEXT PRIMARY KEY,
                    tool TEXT NOT NULL REFERENCES adapters(name),
                    name TEXT NOT NULL,
                    test_plan JSON NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    UNIQUE(tool, name)
                )
            """)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS runs (
                    id TEXT PRIMARY KEY,
                    scenario_id TEXT NOT NULL REFERENCES test_scenarios(id),
                    tool TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending',
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    started_at TEXT,
                    completed_at TEXT,
                    error TEXT,
                    summary JSON,
                    raw_output_path TEXT,
                    metadata JSON DEFAULT '{}'
                )
            """)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """)
        }
    }

    fun insertAdapter(name: String, supportedTypes: String, version: String, toolVersions: String) {
        conn.prepareStatement("INSERT OR IGNORE INTO adapters VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, name)
            ps.setString(2, supportedTypes)
            ps.setString(3, version)
            ps.setString(4, toolVersions)
            ps.execute()
        }
    }

    fun listAdapters(): List<AdapterRow> {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT name, supported_types, adapter_version, tool_versions FROM adapters").use { rs ->
                val result = mutableListOf<AdapterRow>()
                while (rs.next()) {
                    result.add(AdapterRow(rs.getString("name"), rs.getString("supported_types"),
                        rs.getString("adapter_version"), rs.getString("tool_versions")))
                }
                return result
            }
        }
    }

    fun insertScenario(tool: String, name: String, testPlan: String): String? {
        val id = UUID.randomUUID().toString()
        val rows = conn.prepareStatement("INSERT OR IGNORE INTO test_scenarios (id, tool, name, test_plan) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, tool)
            ps.setString(3, name)
            ps.setString(4, testPlan)
            ps.executeUpdate()
        }
        return if (rows > 0) id else getScenario(tool, name)?.id
    }

    fun getScenario(tool: String, name: String): ScenarioRow? {
        conn.prepareStatement("SELECT id, tool, name, test_plan, created_at FROM test_scenarios WHERE tool = ? AND name = ?").use { ps ->
            ps.setString(1, tool)
            ps.setString(2, name)
            ps.executeQuery().use { rs ->
                return if (rs.next()) ScenarioRow(rs.getString("id"), rs.getString("tool"),
                    rs.getString("name"), rs.getString("test_plan"), rs.getString("created_at")) else null
            }
        }
    }

    fun getScenarioById(id: String): ScenarioRow? {
        conn.prepareStatement("SELECT id, tool, name, test_plan, created_at FROM test_scenarios WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                return if (rs.next()) ScenarioRow(rs.getString("id"), rs.getString("tool"),
                    rs.getString("name"), rs.getString("test_plan"), rs.getString("created_at")) else null
            }
        }
    }

    fun insertRun(scenarioId: String, tool: String): String? {
        val id = UUID.randomUUID().toString()
        conn.prepareStatement("INSERT INTO runs (id, scenario_id, tool) VALUES (?, ?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, scenarioId)
            ps.setString(3, tool)
            ps.execute()
        }
        return id
    }

    fun getRun(runId: String): RunRow? {
        conn.prepareStatement("SELECT * FROM runs WHERE id = ?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) RunRow(
                    rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                    rs.getString("status"), rs.getString("created_at"),
                    rs.getString("started_at"), rs.getString("completed_at"),
                    rs.getString("error"), rs.getString("summary"),
                    rs.getString("raw_output_path"), rs.getString("metadata")
                ) else null
            }
        }
    }

    fun updateRunStatus(runId: String, status: String, summary: String? = null,
                        error: String? = null, rawOutputPath: String? = null) {
        val now = Instant.now().toString()
        conn.prepareStatement("""
            UPDATE runs SET status = ?, summary = ?, error = ?, raw_output_path = ?,
                started_at = CASE WHEN ? = 'running' AND started_at IS NULL THEN ? ELSE started_at END,
                completed_at = CASE WHEN ? = 'completed' OR ? = 'failed' THEN ? ELSE completed_at END
            WHERE id = ?
        """).use { ps ->
            ps.setString(1, status)
            ps.setString(2, summary)
            ps.setString(3, error)
            ps.setString(4, rawOutputPath)
            ps.setString(5, status); ps.setString(6, now)
            ps.setString(7, status); ps.setString(8, status); ps.setString(9, now)
            ps.setString(10, runId)
            ps.execute()
        }
    }

    fun listRuns(scenarioId: String): List<RunRow> {
        conn.prepareStatement("SELECT * FROM runs WHERE scenario_id = ? ORDER BY created_at DESC").use { ps ->
            ps.setString(1, scenarioId)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<RunRow>()
                while (rs.next()) {
                    result.add(RunRow(rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                        rs.getString("status"), rs.getString("created_at"),
                        rs.getString("started_at"), rs.getString("completed_at"),
                        rs.getString("error"), rs.getString("summary"),
                        rs.getString("raw_output_path"), rs.getString("metadata")))
                }
                return result
            }
        }
    }

    fun getPendingOrRunningRun(): RunRow? {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("""
                SELECT * FROM runs WHERE status IN ('pending', 'queued', 'running') 
                ORDER BY created_at ASC LIMIT 1
            """).use { rs ->
                return if (rs.next()) RunRow(
                    rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                    rs.getString("status"), rs.getString("created_at"),
                    rs.getString("started_at"), rs.getString("completed_at"),
                    rs.getString("error"), rs.getString("summary"),
                    rs.getString("raw_output_path"), rs.getString("metadata")
                ) else null
            }
        }
    }

    fun getQueuedRuns(): List<RunRow> {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM runs WHERE status = 'queued' ORDER BY created_at ASC").use { rs ->
                val result = mutableListOf<RunRow>()
                while (rs.next()) {
                    result.add(RunRow(rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                        rs.getString("status"), rs.getString("created_at"),
                        rs.getString("started_at"), rs.getString("completed_at"),
                        rs.getString("error"), rs.getString("summary"),
                        rs.getString("raw_output_path"), rs.getString("metadata")))
                }
                return result
            }
        }
    }

    fun close() {
        _conn?.close()
        _conn = null
    }
}
