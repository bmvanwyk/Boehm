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
    private val lock = Any()
    private val conn: Connection
        get() = synchronized(lock) {
            if (_conn == null) {
                Class.forName("org.sqlite.JDBC")
                val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
                c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                initSchema(c)
                _conn = c
            }
            _conn!!
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
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS baselines (
                    scenario_id TEXT PRIMARY KEY REFERENCES test_scenarios(id),
                    run_id TEXT NOT NULL REFERENCES runs(id),
                    tagged_at TEXT NOT NULL
                )
            """)
            // Ensure schema_version is at least 1
            val rs = stmt.executeQuery("SELECT COALESCE(MAX(version),0) as v FROM schema_version")
            val cur = if (rs.next()) rs.getInt("v") else 0
            rs.close()
            if (cur < 1) {
                c.prepareStatement("INSERT OR IGNORE INTO schema_version(version, applied_at) VALUES (1, ?)").use { ps ->
                    ps.setString(1, Instant.now().toString())
                    ps.execute()
                }
            }
        }
    }

    fun getSchemaVersion(): Int {
        synchronized(lock) {
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COALESCE(MAX(version),0) as v FROM schema_version").use { rs ->
                    return if (rs.next()) rs.getInt("v") else 0
                }
            }
        }
    }

    fun setSchemaVersion(version: Int) {
        synchronized(lock) {
            conn.prepareStatement("INSERT OR IGNORE INTO schema_version(version, applied_at) VALUES (?, ?)").use { ps ->
                ps.setInt(1, version)
                ps.setString(2, Instant.now().toString())
                ps.execute()
            }
        }
    }

    fun insertAdapter(name: String, supportedTypes: String, version: String, toolVersions: String) {
        synchronized(lock) {
            conn.prepareStatement("INSERT OR IGNORE INTO adapters VALUES (?, ?, ?, ?)").use { ps ->
                ps.setString(1, name)
                ps.setString(2, supportedTypes)
                ps.setString(3, version)
                ps.setString(4, toolVersions)
                ps.execute()
            }
        }
    }

    fun listAdapters(): List<AdapterRow> {
        synchronized(lock) {
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
    }

    fun insertScenario(tool: String, name: String, testPlan: String): String? {
        synchronized(lock) {
            val id = UUID.randomUUID().toString()
            conn.prepareStatement("""
                INSERT INTO test_scenarios (id, tool, name, test_plan, created_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(tool, name) DO UPDATE SET test_plan = excluded.test_plan
            """).use { ps ->
                ps.setString(1, id)
                ps.setString(2, tool)
                ps.setString(3, name)
                ps.setString(4, testPlan)
                ps.setString(5, Instant.now().toString())
                ps.executeUpdate()
            }
            return getScenario(tool, name)?.id
        }
    }

    fun getScenario(tool: String, name: String): ScenarioRow? {
        synchronized(lock) {
            conn.prepareStatement("SELECT id, tool, name, test_plan, created_at FROM test_scenarios WHERE tool = ? AND name = ?").use { ps ->
                ps.setString(1, tool)
                ps.setString(2, name)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) ScenarioRow(rs.getString("id"), rs.getString("tool"),
                        rs.getString("name"), rs.getString("test_plan"), rs.getString("created_at")) else null
                }
            }
        }
    }

    fun getScenarioById(id: String): ScenarioRow? {
        synchronized(lock) {
            conn.prepareStatement("SELECT id, tool, name, test_plan, created_at FROM test_scenarios WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) ScenarioRow(rs.getString("id"), rs.getString("tool"),
                        rs.getString("name"), rs.getString("test_plan"), rs.getString("created_at")) else null
                }
            }
        }
    }

    fun insertRun(scenarioId: String, tool: String): String? {
        synchronized(lock) {
            val id = UUID.randomUUID().toString()
            conn.prepareStatement("INSERT INTO runs (id, scenario_id, tool, created_at) VALUES (?, ?, ?, ?)").use { ps ->
                ps.setString(1, id)
                ps.setString(2, scenarioId)
                ps.setString(3, tool)
                ps.setString(4, Instant.now().toString())
                ps.execute()
            }
            return id
        }
    }

    fun getRun(runId: String): RunRow? {
        synchronized(lock) {
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
    }

    fun updateRunStatus(runId: String, status: String, summary: String? = null,
                        error: String? = null, rawOutputPath: String? = null,
                        metadata: String? = null) {
        synchronized(lock) {
            val now = Instant.now().toString()
            conn.prepareStatement("""
                UPDATE runs SET status = ?, summary = ?, error = ?, raw_output_path = ?, metadata = ?,
                    started_at = CASE WHEN ? = 'running' AND started_at IS NULL THEN ? ELSE started_at END,
                    completed_at = CASE WHEN ? = 'completed' OR ? = 'failed' THEN ? ELSE completed_at END
                WHERE id = ?
            """).use { ps ->
                ps.setString(1, status)
                ps.setString(2, summary)
                ps.setString(3, error)
                ps.setString(4, rawOutputPath)
                ps.setString(5, metadata)
                ps.setString(6, status); ps.setString(7, now)
                ps.setString(8, status); ps.setString(9, status); ps.setString(10, now)
                ps.setString(11, runId)
                ps.execute()
            }
        }
    }

    fun listRuns(scenarioId: String): List<RunRow> {
        synchronized(lock) {
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
    }

    fun getPendingOrRunningRun(): RunRow? {
        synchronized(lock) {
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
    }

    fun getQueuedRuns(): List<RunRow> {
        synchronized(lock) {
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
    }

    fun failInterruptedRuns(): Int {
        synchronized(lock) {
            val now = Instant.now().toString()
            return conn.prepareStatement("""
                UPDATE runs SET status = 'failed', error = 'interrupted: server restarted', completed_at = ?
                WHERE status = 'running'
            """).use { ps ->
                ps.setString(1, now)
                ps.executeUpdate()
            }
        }
    }

    fun setBaseline(scenarioId: String, runId: String) {
        synchronized(lock) {
            conn.prepareStatement("""
                INSERT INTO baselines (scenario_id, run_id, tagged_at) VALUES (?, ?, ?)
                ON CONFLICT(scenario_id) DO UPDATE SET run_id = excluded.run_id, tagged_at = excluded.tagged_at
            """).use { ps ->
                ps.setString(1, scenarioId)
                ps.setString(2, runId)
                ps.setString(3, Instant.now().toString())
                ps.execute()
            }
        }
    }

    fun getBaselineRunId(scenarioId: String): String? {
        synchronized(lock) {
            conn.prepareStatement("SELECT run_id FROM baselines WHERE scenario_id = ?").use { ps ->
                ps.setString(1, scenarioId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("run_id") else null
                }
            }
        }
    }

    /** Recently finished runs, newest first. In-flight runs are excluded. */
    fun listRecentRuns(tool: String?, testName: String?, limit: Int): List<RunRow> {
        synchronized(lock) {
            conn.prepareStatement("""
                SELECT r.* FROM runs r JOIN test_scenarios s ON r.scenario_id = s.id
                WHERE (? IS NULL OR r.tool = ?) AND (? IS NULL OR s.name = ?)
                  AND r.status IN ('completed', 'failed', 'cancelled')
                ORDER BY r.created_at DESC LIMIT ?
            """).use { ps ->
                ps.setString(1, tool); ps.setString(2, tool)
                ps.setString(3, testName); ps.setString(4, testName)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<RunRow>()
                    while (rs.next()) {
                        result.add(RunRow(
                            rs.getString("id"), rs.getString("scenario_id"), rs.getString("tool"),
                            rs.getString("status"), rs.getString("created_at"),
                            rs.getString("started_at"), rs.getString("completed_at"),
                            rs.getString("error"), rs.getString("summary"),
                            rs.getString("raw_output_path"), rs.getString("metadata")))
                    }
                    return result
                }
            }
        }
    }

    /** Cancels a run that has not started yet. Returns true if the status changed. */
    fun cancelQueuedRun(runId: String): Boolean {
        synchronized(lock) {
            conn.prepareStatement("""
                UPDATE runs SET status = 'cancelled', completed_at = ?
                WHERE id = ? AND status IN ('pending', 'queued')
            """).use { ps ->
                ps.setString(1, Instant.now().toString())
                ps.setString(2, runId)
                return ps.executeUpdate() > 0
            }
        }
    }

    fun close() {
        synchronized(lock) {
            _conn?.close()
            _conn = null
        }
    }
}
