package com.github.sup3rmar10.migrator;

import static com.github.sup3rmar10.migrator.Fixtures.column;
import static com.github.sup3rmar10.migrator.Fixtures.count;
import static com.github.sup3rmar10.migrator.Fixtures.exec;
import static com.github.sup3rmar10.migrator.Fixtures.migrator;
import static com.github.sup3rmar10.migrator.Fixtures.states;
import static com.github.sup3rmar10.migrator.Fixtures.tableExists;
import static com.github.sup3rmar10.migrator.Fixtures.url;
import static com.github.sup3rmar10.migrator.Fixtures.write;
import static com.github.sup3rmar10.migrator.Fixtures.writeBasic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigratorTest {

    // ------------------------------------------------------------ migrate basics

    @Test
    void migrateAppliesVersionedThenRepeatableAndRecordsHistory(@TempDir Path dir) throws Exception {
        writeBasic(dir);
        String url = url();
        Migrator.MigrateResult r = migrator(url, dir).migrate();
        assertEquals(List.of("V1__create_widgets.sql", "V2__add_price.sql", "R__widget_names.sql"), r.applied());
        assertEquals(0L, count(url, "SELECT COUNT(*) FROM widget_names"), "the repeatable view must exist");
        assertEquals(List.of("1", "2", "null"), column(url, "SELECT COALESCE(version, 'null') FROM schema_history ORDER BY installed_rank"));
        assertEquals(List.of("VERSIONED", "VERSIONED", "REPEATABLE"), column(url, "SELECT type FROM schema_history ORDER BY installed_rank"));
    }

    @Test
    void historyRowHasAllFields(@TempDir Path dir) {
        String sql = "CREATE TABLE widgets (id INT PRIMARY KEY);\n";
        write(dir, "V1__create_widgets.sql", sql);
        String url = url();
        migrator(url, dir).migrate();
        assertEquals("create widgets", column(url, "SELECT description FROM schema_history").get(0));
        assertEquals("V1__create_widgets.sql", column(url, "SELECT script FROM schema_history").get(0));
        assertEquals(Checksums.checksum(sql), column(url, "SELECT checksum FROM schema_history").get(0));
        assertEquals("TRUE", column(url, "SELECT success FROM schema_history").get(0).toUpperCase());
        assertEquals(1L, count(url, "SELECT COUNT(*) FROM schema_history WHERE installed_on IS NOT NULL AND execution_ms >= 0"));
    }

    @Test
    void secondMigrateIsANoOp(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        assertTrue(migrator(url, dir).migrate().applied().isEmpty());
        assertEquals(3L, count(url, "SELECT COUNT(*) FROM schema_history"));
    }

    @Test
    void versionsRunInNumericOrderNotTextOrder(@TempDir Path dir) {
        write(dir, "V1.10__ten.sql", "CREATE TABLE t10 (id INT);");
        write(dir, "V1.9__nine.sql", "CREATE TABLE t9 (id INT);");
        write(dir, "V1.2__two.sql", "CREATE TABLE t2 (id INT);");
        List<String> order = new ArrayList<>();
        migrator(url(), dir, c -> c.listener(m -> order.add(m.script()))).migrate();
        assertEquals(List.of("V1.2__two.sql", "V1.9__nine.sql", "V1.10__ten.sql"), order);
    }

    @Test
    void multiStatementScriptWithSemicolonsInsideStringsRuns(@TempDir Path dir) {
        write(dir, "V1__t.sql", "CREATE TABLE t (id INT, note VARCHAR(50));\n"
                + "INSERT INTO t VALUES (1, 'a;b'); -- trailing; comment\n"
                + "INSERT INTO t VALUES (2, 'it''s');\n");
        String url = url();
        migrator(url, dir).migrate();
        assertEquals(List.of("a;b", "it's"), column(url, "SELECT note FROM t ORDER BY id"));
    }

    @Test
    void emptyScriptIsRecordedAsSuccess(@TempDir Path dir) {
        write(dir, "V1__nothing.sql", "-- just a comment\n");
        String url = url();
        migrator(url, dir).migrate();
        assertEquals(1L, count(url, "SELECT COUNT(*) FROM schema_history WHERE success"));
    }

    @Test
    void customHistoryTableName(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir, c -> c.historyTable("my_history")).migrate();
        assertEquals(3L, count(url, "SELECT COUNT(*) FROM my_history"));
        assertFalse(tableExists(url, "SCHEMA_HISTORY"));
        assertThrows(IllegalArgumentException.class, () -> new MigratorConfig().historyTable("x; DROP TABLE y"));
    }

    // ------------------------------------------------------------ repeatable

    @Test
    void repeatableReRunsOnlyWhenItsContentChanges(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        assertTrue(migrator(url, dir).migrate().applied().isEmpty());

        write(dir, "R__widget_names.sql", "CREATE OR REPLACE VIEW widget_names AS SELECT name, id FROM widgets;\n");
        assertTrue(states(migrator(url, dir)).contains("OUTDATED"));
        assertEquals(List.of("R__widget_names.sql"), migrator(url, dir).migrate().applied());
        assertEquals(4L, count(url, "SELECT COUNT(*) FROM schema_history"));
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUPERSEDED", "SUCCESS"), states(migrator(url, dir)));
    }

    @Test
    void repeatableRunsAfterNewVersionedEvenIfAddedLater(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        write(dir, "V3__more.sql", "CREATE TABLE more (id INT);");
        write(dir, "R__widget_names.sql", "CREATE OR REPLACE VIEW widget_names AS SELECT id FROM widgets;");
        List<String> order = new ArrayList<>();
        migrator(url, dir, c -> c.listener(m -> order.add(m.script()))).migrate();
        assertEquals(List.of("V3__more.sql", "R__widget_names.sql"), order);
    }

    // ------------------------------------------------------------ validate

    @Test
    void validateIsCleanAfterMigrate(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        assertTrue(migrator(url, dir).validate().isEmpty());
    }

    @Test
    void editingAnAppliedFileIsChecksumDriftAndMigrateRefuses(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        write(dir, "V2__add_price.sql", "ALTER TABLE widgets ADD COLUMN price BIGINT;\n");
        write(dir, "V3__new.sql", "CREATE TABLE fresh (id INT);");

        List<Migrator.Issue> issues = migrator(url, dir).validate();
        assertEquals(1, issues.size());
        assertEquals(Migrator.IssueKind.CHECKSUM_MISMATCH, issues.get(0).kind());
        assertTrue(issues.get(0).message().contains("V2__add_price.sql"));

        ValidationException e = assertThrows(ValidationException.class, () -> migrator(url, dir).migrate());
        assertEquals(Migrator.IssueKind.CHECKSUM_MISMATCH, e.issues().get(0).kind());
        assertFalse(tableExists(url, "FRESH"), "nothing may be applied when validation fails");
    }

    @Test
    void lineEndingAndTrailingWhitespaceEditsAreNotDrift(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        write(dir, "V1__create_widgets.sql", "CREATE TABLE widgets (id INT PRIMARY KEY, name VARCHAR(50));   \r\n\r\n");
        assertTrue(migrator(url, dir).validate().isEmpty());
    }

    @Test
    void deletingAnAppliedFileIsReportedAsMissing(@TempDir Path dir) throws Exception {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        Files.delete(dir.resolve("V2__add_price.sql"));
        List<Migrator.Issue> issues = migrator(url, dir).validate();
        assertEquals(1, issues.size());
        assertEquals(Migrator.IssueKind.MISSING_FILE, issues.get(0).kind());
        assertTrue(states(migrator(url, dir)).contains("MISSING"));
        assertThrows(ValidationException.class, () -> migrator(url, dir).migrate());
    }

    @Test
    void pendingVersionOlderThanLatestAppliedIsOutOfOrder(@TempDir Path dir) {
        write(dir, "V1__a.sql", "CREATE TABLE a (id INT);");
        write(dir, "V3__c.sql", "CREATE TABLE c (id INT);");
        String url = url();
        migrator(url, dir).migrate();
        write(dir, "V2__b.sql", "CREATE TABLE b (id INT);");

        List<Migrator.Issue> issues = migrator(url, dir).validate();
        assertEquals(1, issues.size());
        assertEquals(Migrator.IssueKind.OUT_OF_ORDER, issues.get(0).kind());
        assertTrue(states(migrator(url, dir)).contains("IGNORED"));
        assertThrows(ValidationException.class, () -> migrator(url, dir).migrate());
        assertFalse(tableExists(url, "B"));
    }

    @Test
    void outOfOrderFlagAllowsThePendingOlderVersion(@TempDir Path dir) {
        write(dir, "V1__a.sql", "CREATE TABLE a (id INT);");
        write(dir, "V3__c.sql", "CREATE TABLE c (id INT);");
        String url = url();
        migrator(url, dir).migrate();
        write(dir, "V2__b.sql", "CREATE TABLE b (id INT);");

        Migrator allowed = migrator(url, dir, c -> c.outOfOrder(true));
        assertTrue(allowed.validate().isEmpty());
        assertEquals(List.of("V2__b.sql"), allowed.migrate().applied());
        assertTrue(tableExists(url, "B"));
        assertEquals(List.of("1", "3", "2"), column(url, "SELECT version FROM schema_history ORDER BY installed_rank"));
    }

    @Test
    void gapsAreWarningsByDefaultAndErrorsWhenAsked(@TempDir Path dir) {
        write(dir, "V1__a.sql", "SELECT 1;");
        write(dir, "V3__c.sql", "SELECT 1;");
        String url = url();
        assertEquals(1, migrator(url, dir).report().warnings().size());
        assertTrue(migrator(url, dir).validate().isEmpty());
        List<Migrator.Issue> issues = migrator(url, dir, c -> c.failOnGaps(true)).validate();
        assertEquals(Migrator.IssueKind.GAP, issues.get(0).kind());
    }

    // ------------------------------------------------------------ dry run and info

    @Test
    void dryRunListsThePlanAndChangesNothing(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        Migrator.Plan plan = migrator(url, dir).dryRun();
        assertEquals(3, plan.steps().size());
        assertEquals("V1__create_widgets.sql", plan.steps().get(0).migration().script());
        assertEquals(1, plan.steps().get(0).statements());
        assertTrue(Reports.plan(plan).contains("would apply 3 migration(s)"));
        assertFalse(tableExists(url, "SCHEMA_HISTORY"), "dry run must not even create the history table");
        assertFalse(tableExists(url, "WIDGETS"));
    }

    @Test
    void dryRunAfterPartialMigrateShowsOnlyWhatIsPending(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        write(dir, "V3__more.sql", "CREATE TABLE more (id INT);");
        Migrator.Plan plan = migrator(url, dir).dryRun();
        assertEquals(List.of("V3__more.sql"), plan.steps().stream().map(s -> s.migration().script()).toList());
        assertFalse(tableExists(url, "MORE"));
    }

    @Test
    void dryRunReportsUnsplittableScripts(@TempDir Path dir) {
        write(dir, "V1__broken.sql", "SELECT 'never closed;");
        Migrator.Plan plan = migrator(url(), dir).dryRun();
        assertTrue(plan.steps().get(0).error().contains("Unterminated string literal"));
    }

    @Test
    void infoShowsStatesForAppliedAndPending(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        write(dir, "V3__more.sql", "SELECT 1;");
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "PENDING"), states(migrator(url, dir)));
        assertTrue(Reports.infoTable(migrator(url, dir).info()).contains("PENDING"));
    }

    // ------------------------------------------------------------ failures

    private void writeFailing(Path dir) {
        write(dir, "V1__ok.sql", "CREATE TABLE a (id INT PRIMARY KEY);");
        write(dir, "V2__bad.sql", "CREATE TABLE b (id INT PRIMARY KEY);\nINSERT INTO b VALUES (1);\nINSERT INTO b VALUES (1);\n");
        write(dir, "V3__later.sql", "CREATE TABLE c (id INT);");
    }

    @Test
    void failedMigrationIsRecordedAsNotSuccessful(@TempDir Path dir) {
        writeFailing(dir);
        String url = url();
        MigrationException e = assertThrows(MigrationException.class, () -> migrator(url, dir).migrate());
        assertTrue(e.getMessage().contains("V2__bad.sql"), e.getMessage());
        assertTrue(e.getMessage().contains("statement 3 of 3"), e.getMessage());
        assertEquals(List.of("1", "2"), column(url, "SELECT version FROM schema_history ORDER BY installed_rank"));
        assertEquals(List.of("TRUE", "FALSE"), column(url, "SELECT success FROM schema_history ORDER BY installed_rank")
                .stream().map(String::toUpperCase).toList());
        assertFalse(tableExists(url, "C"), "migrations after the failure must not run");
    }

    @Test
    void failureRollsBackDmlButDdlStaysBecauseH2AutoCommitsDdl(@TempDir Path dir) {
        writeFailing(dir);
        String url = url();
        assertThrows(MigrationException.class, () -> migrator(url, dir).migrate());
        assertTrue(tableExists(url, "B"), "H2 DDL auto-commits, so the half-applied table is still there");
        assertEquals(0L, count(url, "SELECT COUNT(*) FROM b"), "the INSERT before the failing statement must be rolled back");
    }

    @Test
    void migrateRefusesAfterAFailureUntilRepair(@TempDir Path dir) {
        writeFailing(dir);
        String url = url();
        assertThrows(MigrationException.class, () -> migrator(url, dir).migrate());
        ValidationException refused = assertThrows(ValidationException.class, () -> migrator(url, dir).migrate());
        assertEquals(Migrator.IssueKind.FAILED_MIGRATION, refused.issues().get(0).kind());
        assertTrue(refused.getMessage().contains("repair"), refused.getMessage());
        assertTrue(states(migrator(url, dir)).contains("FAILED"));
    }

    @Test
    void repairThenFixedScriptCompletesTheRun(@TempDir Path dir) {
        writeFailing(dir);
        String url = url();
        assertThrows(MigrationException.class, () -> migrator(url, dir).migrate());

        exec(url, "DROP TABLE b");                               // manual clean-up of the half-applied DDL
        write(dir, "V2__bad.sql", "CREATE TABLE b (id INT PRIMARY KEY);\nINSERT INTO b VALUES (1);\n");
        Migrator.RepairResult repaired = migrator(url, dir).repair();
        assertEquals(1, repaired.removedFailed());

        assertEquals(List.of("V2__bad.sql", "V3__later.sql"), migrator(url, dir).migrate().applied());
        assertEquals(1L, count(url, "SELECT COUNT(*) FROM b"));
        assertEquals(0L, count(url, "SELECT COUNT(*) FROM schema_history WHERE NOT success"));
    }

    @Test
    void repairRealignsChecksumsOfEditedFiles(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        String edited = "ALTER TABLE widgets ADD COLUMN price BIGINT;\n";
        write(dir, "V2__add_price.sql", edited);
        assertFalse(migrator(url, dir).validate().isEmpty());

        Migrator.RepairResult r = migrator(url, dir).repair();
        assertEquals(List.of("V2__add_price.sql"), r.realigned());
        assertEquals(0, r.removedFailed());
        assertTrue(migrator(url, dir).validate().isEmpty());
        assertEquals(Checksums.checksum(edited), column(url, "SELECT checksum FROM schema_history WHERE version = '2'").get(0));
    }

    @Test
    void scriptThatCannotBeSplitFailsBeforeAnythingRuns(@TempDir Path dir) {
        write(dir, "V1__ok.sql", "CREATE TABLE a (id INT);");
        write(dir, "V2__broken.sql", "SELECT 'never closed;");
        String url = url();
        MigrationException e = assertThrows(MigrationException.class, () -> migrator(url, dir).migrate());
        assertTrue(e.getMessage().contains("V2__broken.sql"), e.getMessage());
        assertFalse(tableExists(url, "A"), "V1 must not run when V2 cannot even be parsed");
    }

    // ------------------------------------------------------------ baseline

    @Test
    void baselineSkipsOlderFilesAndAppliesNewerOnes(@TempDir Path dir) {
        write(dir, "V1__old.sql", "CREATE TABLE old_one (id INT);");
        write(dir, "V2__new.sql", "CREATE TABLE new_one (id INT);");
        String url = url();
        migrator(url, dir).baseline(Version.parse("1"), "existing schema");
        assertEquals(List.of("BASELINE", "BELOW_BASELINE", "PENDING"), states(migrator(url, dir)));
        assertEquals(List.of("V2__new.sql"), migrator(url, dir).migrate().applied());
        assertFalse(tableExists(url, "OLD_ONE"));
        assertTrue(tableExists(url, "NEW_ONE"));
    }

    @Test
    void baselineIsRefusedWhenHistoryIsNotEmpty(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        MigrationException e = assertThrows(MigrationException.class, () -> migrator(url, dir).baseline(Version.parse("5"), "x"));
        assertTrue(e.getMessage().contains("not empty"));
    }

    // ------------------------------------------------------------ locking

    @Test
    void twoConcurrentMigratorsApplyEachMigrationExactlyOnce(@TempDir Path dir) throws Exception {
        writeBasic(dir);
        String url = url();
        CountDownLatch aInside = new CountDownLatch(1);
        Migrator a = migrator(url, dir, c -> c.listener(m -> {
            aInside.countDown();
            sleep(250);
        }));
        Migrator b = migrator(url, dir, c -> c.lockTimeoutMs(20_000));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Migrator.MigrateResult> fa = pool.submit(a::migrate);
            assertTrue(aInside.await(10, TimeUnit.SECONDS));
            Future<Migrator.MigrateResult> fb = pool.submit(b::migrate);  // must wait for A, then find nothing to do
            Migrator.MigrateResult ra = fa.get(30, TimeUnit.SECONDS);
            Migrator.MigrateResult rb = fb.get(30, TimeUnit.SECONDS);
            assertEquals(3, ra.applied().size());
            assertEquals(0, rb.applied().size());
            assertEquals(3L, count(url, "SELECT COUNT(*) FROM schema_history WHERE success"));
            assertEquals(3L, count(url, "SELECT COUNT(DISTINCT installed_rank) FROM schema_history"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waitingMigratorFailsClearlyWhenTheTimeoutExpires(@TempDir Path dir) throws Exception {
        writeBasic(dir);
        String url = url();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Migrator holder = migrator(url, dir, c -> c.listener(m -> {
            inside.countDown();
            await(release);
        }));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Migrator.MigrateResult> running = pool.submit(holder::migrate);
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            LockException e = assertThrows(LockException.class, () -> migrator(url, dir, c -> c.lockTimeoutMs(200)).migrate());
            assertTrue(e.getMessage().contains("held by"), e.getMessage());
            assertTrue(e.getMessage().contains("200 ms"), e.getMessage());
            release.countDown();
            assertEquals(3, running.get(30, TimeUnit.SECONDS).applied().size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lockIsReleasedAfterSuccessAndAfterFailure(@TempDir Path failing, @TempDir Path ok) {
        writeFailing(failing);
        String url = url();
        assertThrows(MigrationException.class, () -> migrator(url, failing).migrate());
        assertEquals(0L, count(url, "SELECT COUNT(*) FROM schema_history_lock"));

        writeBasic(ok);
        String url2 = url();
        migrator(url2, ok).migrate();
        assertEquals(0L, count(url2, "SELECT COUNT(*) FROM schema_history_lock"));
    }

    @Test
    void staleLockBlocksUntilUnlock(@TempDir Path dir) {
        writeBasic(dir);
        String url = url();
        migrator(url, dir).migrate();
        exec(url, "INSERT INTO schema_history_lock (id, locked_by, locked_on) VALUES (1, 'crashed-99999', CURRENT_TIMESTAMP)");
        LockException e = assertThrows(LockException.class, () -> migrator(url, dir, c -> c.lockTimeoutMs(100)).migrate());
        assertTrue(e.getMessage().contains("crashed-99999"), e.getMessage());
        assertTrue(e.getMessage().contains("unlock"), e.getMessage());

        assertTrue(migrator(url, dir).unlock());
        assertFalse(migrator(url, dir).unlock());
        assertTrue(migrator(url, dir).migrate().applied().isEmpty());
    }

    // ------------------------------------------------------------ helpers

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
