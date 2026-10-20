package com.github.sup3rmar10.migrator;

import static com.github.sup3rmar10.migrator.Fixtures.url;
import static com.github.sup3rmar10.migrator.Fixtures.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    private record Result(int code, String out, String err) { }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void usageErrorsExitWithCode2() {
        assertEquals(2, run().code());
        assertEquals(2, run("explode", "--url", "x").code());
        assertEquals(2, run("migrate", "--bogus").code());
        assertEquals(2, run("migrate", "--dir", "x").code());          // no --url
        assertEquals(2, run("migrate", "--url", url()).code());        // no --dir
        assertEquals(2, run("baseline", "--url", url(), "--dir", ".").code()); // no --baseline-version
    }

    @Test
    void migrateInfoValidateRoundTrip(@TempDir Path dir) {
        Fixtures.writeBasic(dir);
        String url = url();
        Result migrate = run("migrate", "--url", url, "--dir", dir.toString());
        assertEquals(0, migrate.code(), migrate.err());
        assertTrue(migrate.out().contains("Applied 3 migration(s)"), migrate.out());

        Result again = run("migrate", "--url", url, "--dir", dir.toString());
        assertTrue(again.out().contains("nothing to apply"), again.out());

        Result info = run("info", "--url", url, "--dir", dir.toString());
        assertTrue(info.out().contains("create widgets") && info.out().contains("SUCCESS"), info.out());
        assertEquals(0, run("validate", "--url", url, "--dir", dir.toString()).code());
    }

    @Test
    void validateExitsWith1OnDriftAndMigrateReportsTheError(@TempDir Path dir) {
        Fixtures.writeBasic(dir);
        String url = url();
        run("migrate", "--url", url, "--dir", dir.toString());
        write(dir, "V1__create_widgets.sql", "CREATE TABLE widgets (id BIGINT PRIMARY KEY);\n");
        Result validate = run("validate", "--url", url, "--dir", dir.toString());
        assertEquals(1, validate.code());
        assertTrue(validate.err().contains("CHECKSUM_MISMATCH"), validate.err());
        Result migrate = run("migrate", "--url", url, "--dir", dir.toString());
        assertEquals(1, migrate.code());
        assertTrue(migrate.err().startsWith("ERROR: Validation failed"), migrate.err());
        assertEquals(0, run("repair", "--url", url, "--dir", dir.toString()).code());
        assertEquals(0, run("validate", "--url", url, "--dir", dir.toString()).code());
    }

    @Test
    void dryRunAndBaselineAndOutOfOrderFlag(@TempDir Path dir) {
        write(dir, "V1__a.sql", "CREATE TABLE a (id INT);");
        write(dir, "V3__c.sql", "CREATE TABLE c (id INT);");
        String url = url();
        Result dry = run("dryRun", "--url", url, "--dir", dir.toString());
        assertTrue(dry.out().contains("would apply 2 migration(s)"), dry.out());
        assertTrue(dry.out().contains("Warning: Gap in versions"), dry.out());

        assertEquals(0, run("baseline", "--url", url, "--dir", dir.toString(), "--baseline-version", "1").code());
        assertEquals(0, run("migrate", "--url", url, "--dir", dir.toString()).code());
        write(dir, "V2__b.sql", "CREATE TABLE b (id INT);");
        assertEquals(1, run("migrate", "--url", url, "--dir", dir.toString()).code());
        Result allowed = run("migrate", "--url", url, "--dir", dir.toString(), "--out-of-order");
        assertEquals(0, allowed.code(), allowed.err());
    }

    @Test
    void migratesFromTheClasspath() {
        Result r = run("migrate", "--url", url(), "--classpath", "db/sample");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("V2__create_gadgets.sql"), r.out());
    }

    @Test
    void theShippedSampleMigrationsApplyAndDemoRuns() {
        Result sample = run("migrate", "--url", url(), "--dir", "migrations");
        assertEquals(0, sample.code(), sample.err());
        assertTrue(sample.out().contains("Applied 6 migration(s)"), sample.out());

        Result demo = run("demo", "--dir", "migrations");
        assertEquals(0, demo.code(), demo.err());
        assertTrue(demo.out().contains("[CHECKSUM_MISMATCH] V1.1__widget_price.sql"), demo.out());
        assertTrue(demo.out().contains("realigned: [V1.1__widget_price.sql]"), demo.out());
        assertTrue(demo.out().contains("not drift"), demo.out());
    }
}
