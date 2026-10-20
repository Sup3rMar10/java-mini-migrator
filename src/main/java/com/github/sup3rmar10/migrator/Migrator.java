package com.github.sup3rmar10.migrator;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Applies versioned and repeatable SQL migrations over plain JDBC and records them in a
 * history table.
 *
 * <p>Read-only commands (report, info, validate, dryRun) never create tables and never take the
 * lock. Writing commands (migrate, baseline, repair) create the history and lock tables if
 * needed, take the lock, and release it in a finally block.
 *
 * <p>Transactions: each migration runs on its own connection in a transaction that is
 * committed after the last statement. On databases whose DDL auto-commits (H2, MySQL, Oracle)
 * that only protects the DML statements; see the README for what a half-applied failure looks
 * like and how it is recorded.
 */
public final class Migrator {
    static final String TYPE_VERSIONED = "VERSIONED";
    static final String TYPE_REPEATABLE = "REPEATABLE";
    static final String TYPE_BASELINE = "BASELINE";

    public enum IssueKind { CHECKSUM_MISMATCH, MISSING_FILE, OUT_OF_ORDER, FAILED_MIGRATION, GAP }

    public record Issue(IssueKind kind, String message) { }

    public record InfoRow(String version, String type, String description, String state,
                          Timestamp installedOn, Integer executionMs, String script) { }

    /** Everything a read-only look at the database and the files can tell. */
    public record Report(List<Issue> issues, List<String> warnings, List<InfoRow> rows, List<Migration> pending) { }

    public record MigrateResult(List<String> applied, long totalMs) { }

    public record RepairResult(int removedFailed, List<String> realigned) { }

    public record PlanStep(Migration migration, int statements, String error) { }

    public record Plan(List<PlanStep> steps, List<Issue> issues, List<String> warnings) { }

    private record HistoryEntry(int rank, Version version, String description, String type, String script,
                                String checksum, Timestamp installedOn, int executionMs, boolean success) { }

    private interface LockedAction<T> {
        T run() throws SQLException;
    }

    private final MigratorConfig cfg;
    private final Supplier<List<Migration>> source;
    private final String history;
    private final String lock;

    /** The source is asked for the migrations again on every command, so edits on disk are seen. */
    public Migrator(MigratorConfig cfg, Supplier<List<Migration>> source) {
        this.cfg = cfg;
        this.source = source;
        this.history = cfg.historyTable();
        this.lock = cfg.historyTable() + "_lock";
    }

    public static Migrator forDirectory(MigratorConfig cfg, Path dir) {
        return new Migrator(cfg, () -> MigrationLoader.fromDirectory(dir));
    }

    public static Migrator forClasspath(MigratorConfig cfg, ClassLoader loader, String location) {
        return new Migrator(cfg, () -> MigrationLoader.fromClasspath(loader, location));
    }

    // ---------------------------------------------------------------- read-only commands

    public Report report() {
        List<Migration> local = loadLocal();
        try (Connection c = connect()) {
            return analyze(local, readHistory(c));
        } catch (SQLException e) {
            throw dbError(e);
        }
    }

    public List<InfoRow> info() {
        return report().rows();
    }

    public List<Issue> validate() {
        return report().issues();
    }

    /** Prints nothing and runs nothing: returns what migrate would do right now. */
    public Plan dryRun() {
        Report r = report();
        List<PlanStep> steps = new ArrayList<>();
        for (Migration m : r.pending()) {
            try {
                steps.add(new PlanStep(m, SqlSplitter.split(m.sql()).size(), null));
            } catch (MigrationException e) {
                steps.add(new PlanStep(m, 0, e.getMessage()));
            }
        }
        return new Plan(steps, r.issues(), r.warnings());
    }

    // ---------------------------------------------------------------- writing commands

    public MigrateResult migrate() {
        List<Migration> local = loadLocal();
        return withLock(() -> {
            long started = System.nanoTime();
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                Report report = analyze(local, readHistory(c));
                c.commit();
                if (!report.issues().isEmpty()) {
                    throw new ValidationException(report.issues());
                }
                List<Migration> pending = report.pending();
                // Split everything first: a script that cannot be split fails before anything runs.
                List<List<String>> statements = new ArrayList<>();
                for (Migration m : pending) {
                    try {
                        statements.add(SqlSplitter.split(m.sql()));
                    } catch (MigrationException e) {
                        throw new MigrationException(m.script() + ": " + e.getMessage());
                    }
                }
                int rank = nextRank(c);
                c.commit();
                List<String> applied = new ArrayList<>();
                for (int i = 0; i < pending.size(); i++) {
                    apply(c, pending.get(i), statements.get(i), rank++);
                    applied.add(pending.get(i).script());
                }
                return new MigrateResult(applied, (System.nanoTime() - started) / 1_000_000);
            }
        });
    }

    /** Marks an empty history as already being at {@code version}; older files are then skipped. */
    public void baseline(Version version, String description) {
        withLock(() -> {
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                if (!readHistory(c).isEmpty()) {
                    throw new MigrationException("Cannot baseline: the history table is not empty");
                }
                insertHistory(c, 1, version, description, TYPE_BASELINE, "<< Baseline >>", null, true);
                c.commit();
                return null;
            }
        });
    }

    /**
     * Removes failed history rows and realigns the stored checksum of applied versioned
     * migrations with the files on disk. It does not touch the schema, does not re-run anything,
     * and does not realign repeatable migrations (a changed repeatable just runs again).
     */
    public RepairResult repair() {
        List<Migration> local = loadLocal();
        return withLock(() -> {
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                Map<Version, Migration> byVersion = new HashMap<>();
                for (Migration m : local) {
                    if (!m.isRepeatable()) {
                        byVersion.put(m.version(), m);
                    }
                }
                int removed = 0;
                List<String> realigned = new ArrayList<>();
                for (HistoryEntry h : readHistory(c)) {
                    if (!h.success()) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + history + " WHERE installed_rank = ?")) {
                            ps.setInt(1, h.rank());
                            ps.executeUpdate();
                        }
                        removed++;
                    } else if (TYPE_VERSIONED.equals(h.type())) {
                        Migration m = byVersion.get(h.version());
                        if (m != null && !m.checksum().equals(h.checksum())) {
                            try (PreparedStatement ps = c.prepareStatement("UPDATE " + history + " SET checksum = ? WHERE installed_rank = ?")) {
                                ps.setString(1, m.checksum());
                                ps.setInt(2, h.rank());
                                ps.executeUpdate();
                            }
                            realigned.add(m.script());
                        }
                    }
                }
                c.commit();
                return new RepairResult(removed, realigned);
            }
        });
    }

    /** Force-releases the lock row, for a lock left behind by a crashed process. */
    public boolean unlock() {
        try (Connection c = connect()) {
            if (!tableExists(c, lock)) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + lock + " WHERE id = 1")) {
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw dbError(e);
        }
    }

    // ---------------------------------------------------------------- analysis

    private Report analyze(List<Migration> local, List<HistoryEntry> entries) {
        List<Issue> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<InfoRow> rows = new ArrayList<>();
        List<Migration> pending = new ArrayList<>();

        Map<Version, Migration> localV = new HashMap<>();
        Map<String, Migration> localR = new HashMap<>();
        for (Migration m : local) {
            if (m.isRepeatable()) {
                localR.put(m.description(), m);
            } else {
                localV.put(m.version(), m);
            }
        }

        Version baseline = null;
        Version maxApplied = null;
        Map<Version, HistoryEntry> appliedV = new HashMap<>();
        Map<String, HistoryEntry> latestR = new HashMap<>();
        for (HistoryEntry h : entries) {
            if (!h.success()) {
                continue;
            }
            if (TYPE_BASELINE.equals(h.type())) {
                baseline = max(baseline, h.version());
                maxApplied = max(maxApplied, h.version());
            } else if (TYPE_VERSIONED.equals(h.type())) {
                appliedV.put(h.version(), h);
                maxApplied = max(maxApplied, h.version());
            } else {
                latestR.put(h.description(), h);
            }
        }

        for (HistoryEntry h : entries) {
            String state;
            if (!h.success()) {
                state = "FAILED";
                issues.add(new Issue(IssueKind.FAILED_MIGRATION, label(h) + " (" + h.script() + ") is recorded as FAILED in "
                        + history + ": it may be half-applied. Fix the database by hand, then run repair."));
            } else if (TYPE_BASELINE.equals(h.type())) {
                state = "BASELINE";
            } else if (TYPE_VERSIONED.equals(h.type())) {
                Migration m = localV.get(h.version());
                if (m == null) {
                    state = "MISSING";
                    issues.add(new Issue(IssueKind.MISSING_FILE, label(h) + " (" + h.script()
                            + ") is recorded as applied but no matching file was found"));
                } else if (!Objects.equals(m.checksum(), h.checksum())) {
                    state = "DRIFT";
                    issues.add(new Issue(IssueKind.CHECKSUM_MISMATCH, m.script() + " was modified after it was applied (applied checksum "
                            + abbreviate(h.checksum()) + ", file checksum " + abbreviate(m.checksum()) + ")"));
                } else {
                    state = "SUCCESS";
                }
            } else if (latestR.get(h.description()).rank() != h.rank()) {
                state = "SUPERSEDED";
            } else {
                Migration m = localR.get(h.description());
                if (m == null) {
                    state = "MISSING";
                    issues.add(new Issue(IssueKind.MISSING_FILE, label(h) + " (" + h.script()
                            + ") is recorded as applied but no matching file was found"));
                } else if (!Objects.equals(m.checksum(), h.checksum())) {
                    state = "OUTDATED";
                } else {
                    state = "SUCCESS";
                }
            }
            rows.add(new InfoRow(h.version() == null ? "" : h.version().toString(), h.type(), h.description(), state,
                    h.installedOn(), h.executionMs(), h.script()));
        }

        for (Migration m : local) {
            if (m.isRepeatable()) {
                HistoryEntry latest = latestR.get(m.description());
                if (latest == null || !Objects.equals(latest.checksum(), m.checksum())) {
                    pending.add(m);
                    rows.add(pendingRow(m, "PENDING"));
                }
            } else if (appliedV.containsKey(m.version())) {
                continue;
            } else if (baseline != null && m.version().compareTo(baseline) <= 0) {
                rows.add(pendingRow(m, "BELOW_BASELINE"));
            } else if (maxApplied != null && m.version().compareTo(maxApplied) < 0) {
                if (cfg.outOfOrder()) {
                    pending.add(m);
                    rows.add(pendingRow(m, "PENDING"));
                } else {
                    issues.add(new Issue(IssueKind.OUT_OF_ORDER, m.script() + " is pending but version " + m.version()
                            + " is older than the latest applied version " + maxApplied + "; set outOfOrder to allow it"));
                    rows.add(pendingRow(m, "IGNORED"));
                }
            } else {
                pending.add(m);
                rows.add(pendingRow(m, "PENDING"));
            }
        }

        for (String gap : MigrationLoader.findGaps(local)) {
            if (cfg.failOnGaps()) {
                issues.add(new Issue(IssueKind.GAP, gap));
            } else {
                warnings.add(gap);
            }
        }
        return new Report(issues, warnings, rows, pending);
    }

    private static InfoRow pendingRow(Migration m, String state) {
        return new InfoRow(m.isRepeatable() ? "" : m.version().toString(),
                m.isRepeatable() ? TYPE_REPEATABLE : TYPE_VERSIONED, m.description(), state, null, null, m.script());
    }

    private static Version max(Version a, Version b) {
        return a == null || b.compareTo(a) > 0 ? b : a;
    }

    private static String label(HistoryEntry h) {
        return h.version() == null ? "R " + h.description() : "V" + h.version() + " " + h.description();
    }

    private static String abbreviate(String checksum) {
        return checksum == null ? "none" : checksum.substring(0, Math.min(12, checksum.length()));
    }

    private List<Migration> loadLocal() {
        List<Migration> local = new ArrayList<>(source.get());
        local.sort(MigrationLoader.ORDER);
        return local;
    }

    // ---------------------------------------------------------------- applying one migration

    private void apply(Connection c, Migration m, List<String> statements, int rank) throws SQLException {
        // 1. Record the attempt as success=false and commit it, BEFORE running anything. If the
        //    process dies or a DDL statement half-applies, this row is what tells us.
        insertHistory(c, rank, m.version(), m.description(), m.isRepeatable() ? TYPE_REPEATABLE : TYPE_VERSIONED,
                m.script(), m.checksum(), false);
        c.commit();
        cfg.listener().beforeApply(m);
        long started = System.nanoTime();
        int current = 0;
        try (Statement st = c.createStatement()) {
            for (String sql : statements) {
                current++;
                st.execute(sql);
            }
            // 2. Flip the row to success=true in the same transaction as the last statements.
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + history + " SET success = TRUE, execution_ms = ? WHERE installed_rank = ?")) {
                ps.setInt(1, elapsedMs(started));
                ps.setInt(2, rank);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // the original failure is the interesting one
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + history + " SET execution_ms = ? WHERE installed_rank = ?")) {
                ps.setInt(1, elapsedMs(started));
                ps.setInt(2, rank);
                ps.executeUpdate();
                c.commit();
            } catch (SQLException ignored) {
                // best effort: the success=false row already exists
            }
            String firstLine = String.valueOf(e.getMessage()).split("\\R", 2)[0];
            throw new MigrationException("Migration " + m.script() + " failed at statement " + current + " of " + statements.size()
                    + ": " + firstLine + "\n  statement: " + excerpt(statements.get(current - 1))
                    + "\n  The history table now records it as success=false and migrate refuses to continue until you run repair."
                    + "\n  Statements before the failing one may have been applied: DML is rolled back, but DDL auto-commits"
                    + " on databases such as H2, so check the schema by hand first.", e);
        }
    }

    private static int elapsedMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String excerpt(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ");
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "...";
    }

    // ---------------------------------------------------------------- history table

    private void ensureTables(Connection c) throws SQLException {
        createIfMissing(c, history, "CREATE TABLE IF NOT EXISTS " + history + " ("
                + "installed_rank INT NOT NULL PRIMARY KEY, "
                + "version VARCHAR(50), "
                + "description VARCHAR(255) NOT NULL, "
                + "type VARCHAR(20) NOT NULL, "
                + "script VARCHAR(255) NOT NULL, "
                + "checksum VARCHAR(64), "
                + "installed_on TIMESTAMP NOT NULL, "
                + "execution_ms INT NOT NULL, "
                + "success BOOLEAN NOT NULL)");
        createIfMissing(c, lock, "CREATE TABLE IF NOT EXISTS " + lock + " ("
                + "id INT NOT NULL PRIMARY KEY, "
                + "locked_by VARCHAR(200) NOT NULL, "
                + "locked_on TIMESTAMP NOT NULL)");
    }

    private void createIfMissing(Connection c, String table, String ddl) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            // two migrators racing to create the table: fine as long as it exists now
            if (!tableExists(c, table)) {
                throw e;
            }
        }
    }

    private static boolean tableExists(Connection c, String name) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String candidate : new String[] {name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rs = md.getTables(null, null, candidate, null)) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<HistoryEntry> readHistory(Connection c) throws SQLException {
        if (!tableExists(c, history)) {
            return List.of();
        }
        List<HistoryEntry> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT installed_rank, version, description, type, script, checksum, installed_on, "
                     + "execution_ms, success FROM " + history + " ORDER BY installed_rank")) {
            while (rs.next()) {
                String version = rs.getString(2);
                out.add(new HistoryEntry(rs.getInt(1), version == null ? null : Version.parse(version), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7), rs.getInt(8), rs.getBoolean(9)));
            }
        }
        return out;
    }

    private int nextRank(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM " + history)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void insertHistory(Connection c, int rank, Version version, String description, String type, String script,
                               String checksum, boolean success) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + history + " (installed_rank, version, description, type, "
                + "script, checksum, installed_on, execution_ms, success) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)")) {
            ps.setInt(1, rank);
            ps.setString(2, version == null ? null : version.toString());
            ps.setString(3, description);
            ps.setString(4, type);
            ps.setString(5, script);
            ps.setString(6, checksum);
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.setBoolean(8, success);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- lock

    private <T> T withLock(LockedAction<T> action) {
        try (Connection lockConnection = connect()) {
            ensureTables(lockConnection);
            String owner = acquireLock(lockConnection);
            try {
                return action.run();
            } finally {
                releaseLock(lockConnection, owner);
            }
        } catch (SQLException e) {
            throw dbError(e);
        }
    }

    /** Inserts the single lock row (autocommit, so other connections see it at once). */
    private String acquireLock(Connection c) throws SQLException {
        String owner = ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
        long deadline = System.nanoTime() + cfg.lockTimeoutMs() * 1_000_000L;
        while (true) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + lock + " (id, locked_by, locked_on) VALUES (1, ?, ?)")) {
                ps.setString(1, owner);
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.executeUpdate();
                return owner;
            } catch (SQLException e) {
                String holder = lockHolder(c);
                boolean contention = holder != null || (e.getSQLState() != null && e.getSQLState().startsWith("23"));
                if (!contention) {
                    throw e;
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new LockException("Could not acquire the migration lock within " + cfg.lockTimeoutMs()
                            + " ms: it is held by " + (holder == null ? "another migrator" : holder)
                            + ". If that process crashed, release the lock with the unlock command.", e);
                }
                try {
                    Thread.sleep(cfg.lockPollMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LockException("Interrupted while waiting for the migration lock", ie);
                }
            }
        }
    }

    private String lockHolder(Connection c) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT locked_by, locked_on FROM " + lock + " WHERE id = 1")) {
            return rs.next() ? rs.getString(1) + " since " + rs.getTimestamp(2) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private void releaseLock(Connection c, String owner) {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + lock + " WHERE id = 1 AND locked_by = ?")) {
            ps.setString(1, owner);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("warning: could not release the migration lock (" + e.getMessage() + "); use the unlock command");
        }
    }

    // ---------------------------------------------------------------- plumbing

    private Connection connect() throws SQLException {
        if (cfg.url() == null) {
            throw new MigrationException("No JDBC url configured");
        }
        if (cfg.user() == null) {
            return DriverManager.getConnection(cfg.url());
        }
        return DriverManager.getConnection(cfg.url(), cfg.user(), cfg.password() == null ? "" : cfg.password());
    }

    private static MigrationException dbError(SQLException e) {
        return new MigrationException("Database error: " + e.getMessage(), e);
    }
}
