package com.github.sup3rmar10.migrator;

/** Another migrator holds the lock and it did not go away within the timeout. */
public final class LockException extends MigrationException {
    public LockException(String message) {
        super(message);
    }

    public LockException(String message, Throwable cause) {
        super(message, cause);
    }
}
