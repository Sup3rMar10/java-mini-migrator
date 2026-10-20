package com.github.sup3rmar10.migrator;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Plain-text formatting for the CLI output. */
public final class Reports {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Reports() {
    }

    public static String issues(List<Migrator.Issue> issues) {
        StringBuilder sb = new StringBuilder("Validation failed with " + issues.size() + " issue(s):");
        for (Migrator.Issue issue : issues) {
            sb.append("\n  - [").append(issue.kind()).append("] ").append(issue.message());
        }
        return sb.toString();
    }

    public static String infoTable(List<Migrator.InfoRow> rows) {
        List<String[]> table = new ArrayList<>();
        table.add(new String[] {"Version", "Type", "Description", "State", "Installed on", "ms"});
        for (Migrator.InfoRow r : rows) {
            table.add(new String[] {
                r.version(), r.type(), r.description(), r.state(),
                r.installedOn() == null ? "" : format(r.installedOn()),
                r.executionMs() == null ? "" : String.valueOf(r.executionMs())});
        }
        return table(table);
    }

    public static String plan(Migrator.Plan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.steps().isEmpty()) {
            sb.append("Dry run: nothing to apply.");
        } else {
            sb.append("Dry run: would apply ").append(plan.steps().size()).append(" migration(s), in this order:");
            int n = 1;
            for (Migrator.PlanStep s : plan.steps()) {
                sb.append("\n  ").append(n++).append(". ").append(s.migration().script()).append("  [")
                        .append(s.error() == null ? s.statements() + " statement(s)" : "CANNOT SPLIT: " + s.error())
                        .append(", checksum ").append(s.migration().checksum(), 0, 12).append("]");
            }
        }
        for (String w : plan.warnings()) {
            sb.append("\nWarning: ").append(w);
        }
        if (!plan.issues().isEmpty()) {
            sb.append("\nmigrate would be REFUSED: ").append(issues(plan.issues()));
        }
        return sb.toString();
    }

    private static String format(Timestamp ts) {
        return ts.toLocalDateTime().format(TS);
    }

    private static String table(List<String[]> rows) {
        int cols = rows.get(0).length;
        int[] width = new int[cols];
        for (String[] row : rows) {
            for (int i = 0; i < cols; i++) {
                width[i] = Math.max(width[i], row[i].length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                line.append(String.format("%-" + width[i] + "s", rows.get(r)[i]));
                if (i < cols - 1) {
                    line.append("  ");
                }
            }
            sb.append(line.toString().stripTrailing()).append('\n');
            if (r == 0) {
                sb.append("-".repeat(Math.min(120, line.length()))).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}
