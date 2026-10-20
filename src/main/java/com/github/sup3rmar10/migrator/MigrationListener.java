package com.github.sup3rmar10.migrator;

/** Hook called while the lock is held, just before a migration's statements run. */
@FunctionalInterface
public interface MigrationListener {
    void beforeApply(Migration migration);
}
