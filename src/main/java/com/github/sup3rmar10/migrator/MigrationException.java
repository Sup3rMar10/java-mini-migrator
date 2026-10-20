package com.github.sup3rmar10.migrator;

/** Base class for every error the migrator reports on purpose. */
public class MigrationException extends RuntimeException {
    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
