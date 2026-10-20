package com.github.sup3rmar10.migrator;

import java.util.List;

/** Thrown by migrate when validation finds problems; nothing was applied. */
public final class ValidationException extends MigrationException {
    private final List<Migrator.Issue> issues;

    public ValidationException(List<Migrator.Issue> issues) {
        super(Reports.issues(issues));
        this.issues = List.copyOf(issues);
    }

    public List<Migrator.Issue> issues() {
        return issues;
    }
}
