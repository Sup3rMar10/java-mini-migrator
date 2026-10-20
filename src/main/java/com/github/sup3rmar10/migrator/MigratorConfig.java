package com.github.sup3rmar10.migrator;

import java.util.regex.Pattern;

/** Settings for a {@link Migrator}. Fluent, mutable, not thread-safe to change while running. */
public final class MigratorConfig {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private String url;
    private String user;
    private String password;
    private boolean outOfOrder;
    private boolean failOnGaps;
    private String historyTable = "schema_history";
    private long lockTimeoutMs = 10_000;
    private long lockPollMs = 25;
    private MigrationListener listener = m -> { };

    public MigratorConfig url(String url) {
        this.url = url;
        return this;
    }

    public MigratorConfig user(String user) {
        this.user = user;
        return this;
    }

    public MigratorConfig password(String password) {
        this.password = password;
        return this;
    }

    /** Allow applying a pending versioned migration that is older than the latest applied one. */
    public MigratorConfig outOfOrder(boolean outOfOrder) {
        this.outOfOrder = outOfOrder;
        return this;
    }

    /** Treat gaps in the major version sequence as validation errors instead of warnings. */
    public MigratorConfig failOnGaps(boolean failOnGaps) {
        this.failOnGaps = failOnGaps;
        return this;
    }

    /** Name of the history table; the lock table is this name plus "_lock". Plain identifier only. */
    public MigratorConfig historyTable(String historyTable) {
        if (historyTable == null || !IDENTIFIER.matcher(historyTable).matches()) {
            throw new IllegalArgumentException("History table must be a plain identifier ([A-Za-z_][A-Za-z0-9_]*): " + historyTable);
        }
        this.historyTable = historyTable;
        return this;
    }

    public MigratorConfig lockTimeoutMs(long lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
        return this;
    }

    public MigratorConfig lockPollMs(long lockPollMs) {
        this.lockPollMs = lockPollMs;
        return this;
    }

    public MigratorConfig listener(MigrationListener listener) {
        this.listener = listener;
        return this;
    }

    public String url() {
        return url;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public boolean outOfOrder() {
        return outOfOrder;
    }

    public boolean failOnGaps() {
        return failOnGaps;
    }

    public String historyTable() {
        return historyTable;
    }

    public long lockTimeoutMs() {
        return lockTimeoutMs;
    }

    public long lockPollMs() {
        return lockPollMs;
    }

    public MigrationListener listener() {
        return listener;
    }
}
