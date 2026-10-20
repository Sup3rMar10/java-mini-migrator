package com.github.sup3rmar10.migrator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Scripted walk-through: migrate a copy of the sample directory, make a harmless whitespace
 * edit (not drift), make a real edit to an applied file (drift), see migrate refuse, repair,
 * then add a new migration and use dryRun before applying it.
 */
final class Demo {
    private Demo() {
    }

    static void run(Path sampleDir, PrintStream out) {
        Path work = null;
        try {
            work = Files.createTempDirectory("mini-migrator-demo");
            copySql(sampleDir, work);
            String url = "jdbc:h2:mem:demo" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
            Migrator migrator = Migrator.forDirectory(new MigratorConfig().url(url), work);

            out.println("== 1. migrate (sample migrations copied to a temp directory)");
            Migrator.MigrateResult applied = migrator.migrate();
            out.println("Applied " + applied.applied().size() + " migration(s): " + applied.applied());
            out.println();
            out.println("== 2. info");
            out.println(Reports.infoTable(migrator.info()));

            Path v2 = work.resolve("V2__create_orders.sql");
            String original = Files.readString(v2, StandardCharsets.UTF_8);
            out.println();
            out.println("== 3. rewrite V2 with CRLF line endings and trailing spaces: not drift");
            Files.writeString(v2, original.replace("\n", "   \r\n"), StandardCharsets.UTF_8);
            out.println("validate: " + (migrator.validate().isEmpty() ? "OK, no issues" : migrator.validate()));
            Files.writeString(v2, original, StandardCharsets.UTF_8);

            Path v11 = work.resolve("V1.1__widget_price.sql");
            String before = Files.readString(v11, StandardCharsets.UTF_8);
            String after = before.replace("DEFAULT 0", "DEFAULT 100");
            if (before.equals(after)) {
                throw new IllegalStateException("sample file V1.1 does not contain 'DEFAULT 0'; the demo edit needs updating");
            }
            out.println();
            out.println("== 4. edit already-applied V1.1 (DEFAULT 0 -> DEFAULT 100): drift");
            Files.writeString(v11, after, StandardCharsets.UTF_8);
            out.println("validate: " + Reports.issues(migrator.validate()));
            try {
                migrator.migrate();
            } catch (ValidationException e) {
                out.println("migrate refused, nothing applied (" + e.issues().size() + " validation issue)");
            }

            out.println();
            out.println("== 5. repair (accept the edited file as the new truth; the schema is NOT changed)");
            Migrator.RepairResult repaired = migrator.repair();
            out.println("realigned: " + repaired.realigned());
            out.println("validate: " + (migrator.validate().isEmpty() ? "OK, no issues" : migrator.validate()));

            out.println();
            out.println("== 6. add V3, dry run, then migrate");
            Files.writeString(work.resolve("V3__add_order_note.sql"),
                    "ALTER TABLE orders ADD COLUMN note VARCHAR(200);\n", StandardCharsets.UTF_8);
            out.println(Reports.plan(migrator.dryRun()));
            out.println("Applied: " + migrator.migrate().applied());
            out.println();
            out.println(Reports.infoTable(migrator.info()));
        } catch (IOException e) {
            throw new MigrationException("Demo failed: " + e, e);
        } finally {
            if (work != null) {
                deleteQuietly(work);
            }
        }
    }

    private static void copySql(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            throw new MigrationException("Sample migration directory not found: " + from);
        }
        try (Stream<Path> files = Files.list(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().endsWith(".sql")) {
                    Files.copy(p, to.resolve(p.getFileName().toString()));
                }
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir; the OS will clean it up eventually
        }
    }
}
