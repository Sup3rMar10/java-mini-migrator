package com.github.sup3rmar10.migrator;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command line entry point.
 *
 * <pre>
 * java -cp "target/classes:target/lib/*" com.github.sup3rmar10.migrator.Main migrate --url jdbc:h2:file:./data/x --dir ./migrations
 * </pre>
 *
 * Exit codes: 0 success, 1 migration/validation/lock failure, 2 usage error.
 */
public final class Main {
    private static final Set<String> FLAGS = Set.of("--out-of-order", "--fail-on-gaps");
    private static final Set<String> VALUES = Set.of("--url", "--user", "--password", "--dir", "--classpath",
            "--history-table", "--lock-timeout-ms", "--baseline-version", "--baseline-description");

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        String command = args[0];
        Map<String, String> opts = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (FLAGS.contains(a)) {
                flags.add(a);
            } else if (VALUES.contains(a) && i + 1 < args.length) {
                opts.put(a, args[++i]);
            } else {
                err.println("ERROR: unknown option or missing value: " + a);
                usage(err);
                return 2;
            }
        }
        try {
            return dispatch(command, opts, flags, out, err);
        } catch (MigrationException e) {
            err.println("ERROR: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            err.println("ERROR: " + e.getMessage());
            return 2;
        }
    }

    private static int dispatch(String command, Map<String, String> opts, Set<String> flags,
                                PrintStream out, PrintStream err) {
        if (command.equals("demo")) {
            Demo.run(Path.of(opts.getOrDefault("--dir", "migrations")), out);
            return 0;
        }
        if (!List.of("migrate", "info", "validate", "baseline", "repair", "dryRun", "dry-run", "unlock").contains(command)) {
            err.println("ERROR: unknown command: " + command);
            usage(err);
            return 2;
        }
        Migrator migrator = build(opts, flags);
        switch (command) {
            case "migrate" -> {
                Migrator.MigrateResult r = migrator.migrate();
                if (r.applied().isEmpty()) {
                    out.println("Schema is up to date; nothing to apply.");
                } else {
                    out.println("Applied " + r.applied().size() + " migration(s) in " + r.totalMs() + " ms:");
                    r.applied().forEach(s -> out.println("  " + s));
                }
            }
            case "info" -> {
                Migrator.Report report = migrator.report();
                out.println(Reports.infoTable(report.rows()));
                report.warnings().forEach(w -> out.println("Warning: " + w));
            }
            case "validate" -> {
                Migrator.Report report = migrator.report();
                report.warnings().forEach(w -> out.println("Warning: " + w));
                if (!report.issues().isEmpty()) {
                    err.println(Reports.issues(report.issues()));
                    return 1;
                }
                out.println("Validation OK");
            }
            case "dryRun", "dry-run" -> out.println(Reports.plan(migrator.dryRun()));
            case "baseline" -> {
                String version = opts.get("--baseline-version");
                if (version == null) {
                    throw new IllegalArgumentException("baseline needs --baseline-version");
                }
                migrator.baseline(Version.parse(version), opts.getOrDefault("--baseline-description", "baseline"));
                out.println("Baselined at version " + Version.parse(version));
            }
            case "repair" -> {
                Migrator.RepairResult r = migrator.repair();
                out.println("Repair: removed " + r.removedFailed() + " failed entr" + (r.removedFailed() == 1 ? "y" : "ies")
                        + ", realigned checksum of " + r.realigned().size() + " migration(s)" + (r.realigned().isEmpty() ? "" : " " + r.realigned()));
            }
            default -> out.println(migrator.unlock() ? "Lock released" : "No lock was held");
        }
        return 0;
    }

    private static Migrator build(Map<String, String> opts, Set<String> flags) {
        String url = opts.get("--url");
        if (url == null) {
            throw new IllegalArgumentException("--url is required");
        }
        MigratorConfig cfg = new MigratorConfig().url(url)
                .user(opts.get("--user")).password(opts.get("--password"))
                .outOfOrder(flags.contains("--out-of-order")).failOnGaps(flags.contains("--fail-on-gaps"));
        if (opts.containsKey("--history-table")) {
            cfg.historyTable(opts.get("--history-table"));
        }
        if (opts.containsKey("--lock-timeout-ms")) {
            cfg.lockTimeoutMs(Long.parseLong(opts.get("--lock-timeout-ms")));
        }
        if (opts.containsKey("--dir")) {
            return Migrator.forDirectory(cfg, Path.of(opts.get("--dir")));
        }
        if (opts.containsKey("--classpath")) {
            return Migrator.forClasspath(cfg, Main.class.getClassLoader(), opts.get("--classpath"));
        }
        throw new IllegalArgumentException("one of --dir or --classpath is required");
    }

    private static void usage(PrintStream err) {
        err.println("Usage: Main <migrate|info|validate|baseline|repair|dryRun|unlock|demo> --url <jdbc-url> (--dir <path> | --classpath <location>) [options]");
        err.println("Options: --user --password --history-table --lock-timeout-ms --out-of-order --fail-on-gaps");
        err.println("         --baseline-version <v> --baseline-description <text>   (baseline only)");
        err.println("         demo takes only --dir (default ./migrations)");
        err.println("Note: for an in-memory H2 url add ;DB_CLOSE_DELAY=-1 or the database vanishes between commands.");
    }
}
