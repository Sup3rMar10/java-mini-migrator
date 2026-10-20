# java-mini-migrator

A small Flyway-style database schema migration tool in Java 17 over plain JDBC, tested against H2.
It exists to show how a migration tool works (history table, checksums, locking, failure handling),
not to replace Flyway or Liquibase. The only dependencies are H2 (runtime) and JUnit 5 (test).

What it does:

- Versioned migrations `V<version>__<description>.sql` and repeatable migrations `R__<description>.sql`,
  read from a directory or a classpath location (directory or jar).
- Versions are compared numerically per dotted segment, so `V1.10` runs after `V1.9`. Duplicate versions
  (`V1` and `V1.0` count as the same) are rejected; gaps in the major sequence are reported.
- A `schema_history` table records version, description, checksum (SHA-256 of normalised content),
  installed_on, execution_ms and a success flag.
- A lock row in `schema_history_lock` stops two migrators from running at once.
- A small SQL statement splitter that understands `;` inside strings, quoted identifiers and comments.

## Commands

| Command | What it does | Writes to the DB? |
|---|---|---|
| `migrate` | Validates, then applies pending versioned migrations in version order, then changed/new repeatables | yes (takes the lock) |
| `info` | Table of applied and pending migrations with a state per row (SUCCESS, PENDING, FAILED, DRIFT, MISSING, OUTDATED, IGNORED, BELOW_BASELINE, ...) | no |
| `validate` | Reports checksum drift, applied-but-missing files, out-of-order pending versions, failed rows (and gaps with `--fail-on-gaps`). Exit code 1 if any | no |
| `dryRun` | Prints the plan (order, statement count, checksum prefix) and whether migrate would be refused. Does not even create the history table | no |
| `baseline` | Marks an empty history as being at `--baseline-version`; files at or below it are skipped | yes |
| `repair` | Deletes failed history rows and realigns the stored checksum of applied versioned migrations with the files on disk. Does not touch the schema | yes |
| `unlock` | Force-releases a lock row left behind by a crashed process | yes |
| `demo` | Scripted walk-through, see below | in-memory DB only |

Options: `--url`, `--user`, `--password`, `--dir <path>` or `--classpath <location>`, `--history-table`,
`--lock-timeout-ms` (default 10000), `--out-of-order`, `--fail-on-gaps`, `--baseline-version`,
`--baseline-description`. Exit codes: 0 ok, 1 migration/validation/lock failure, 2 usage error.

## How to run

```bash
mvn -q -B verify                      # compile, run the tests, copy h2 to target/lib

# Linux / macOS / Git Bash: use ':' as the classpath separator. On Windows cmd/PowerShell use ';'.
java -cp "target/classes:target/lib/*" com.github.sup3rmar10.migrator.Main demo --dir ./migrations

java -cp "target/classes:target/lib/*" com.github.sup3rmar10.migrator.Main migrate \
     --url "jdbc:h2:file:./target/demo-db" --dir ./migrations
java -cp "target/classes:target/lib/*" com.github.sup3rmar10.migrator.Main info \
     --url "jdbc:h2:file:./target/demo-db" --dir ./migrations
```

The spec-style command `java -cp target/classes ... --url jdbc:h2:mem:x` only works if the H2 jar is also on the
classpath; `target/lib/*` is that. An in-memory URL vanishes when the JVM exits, so use a file URL (or add
`;DB_CLOSE_DELAY=-1` within a single process) when you want the history to survive between commands.

## Demo output (real run)

`demo` copies `migrations/` to a temp directory, applies it to an in-memory H2 database, then:
rewrites V2 with CRLF line endings and trailing spaces (not drift), edits the already-applied V1.1
(drift), shows `migrate` refusing, runs `repair`, and finally adds V3 with a dry run before applying it.

```
== 1. migrate (sample migrations copied to a temp directory)
Applied 6 migration(s): [V1__create_widgets.sql, V1.1__widget_price.sql, V1.2__widget_sku.sql, V1.10__seed_widgets.sql, V2__create_orders.sql, R__widget_summary_view.sql]

== 2. info
Version  Type        Description          State    Installed on         ms
--------------------------------------------------------------------------
1        VERSIONED   create widgets       SUCCESS  2026-10-18 13:02:03  5
1.1      VERSIONED   widget price         SUCCESS  2026-10-18 13:02:03  18
1.2      VERSIONED   widget sku           SUCCESS  2026-10-18 13:02:03  7
1.10     VERSIONED   seed widgets         SUCCESS  2026-10-18 13:02:03  1
2        VERSIONED   create orders        SUCCESS  2026-10-18 13:02:03  5
         REPEATABLE  widget summary view  SUCCESS  2026-10-18 13:02:03  1

== 3. rewrite V2 with CRLF line endings and trailing spaces: not drift
validate: OK, no issues

== 4. edit already-applied V1.1 (DEFAULT 0 -> DEFAULT 100): drift
validate: Validation failed with 1 issue(s):
  - [CHECKSUM_MISMATCH] V1.1__widget_price.sql was modified after it was applied (applied checksum 7301659184cb, file checksum ba43a44648e3)
migrate refused, nothing applied (1 validation issue)

== 5. repair (accept the edited file as the new truth; the schema is NOT changed)
realigned: [V1.1__widget_price.sql]
validate: OK, no issues

== 6. add V3, dry run, then migrate
Dry run: would apply 1 migration(s), in this order:
  1. V3__add_order_note.sql  [1 statement(s), checksum e863cc3dfbff]
Applied: [V3__add_order_note.sql]

Version  Type        Description          State    Installed on         ms
--------------------------------------------------------------------------
1        VERSIONED   create widgets       SUCCESS  2026-10-18 13:02:03  5
...                                       (same rows as above)
3        VERSIONED   add order note       SUCCESS  2026-10-18 13:02:03  9
```

(Timestamps and millisecond figures are from one run and will differ on yours. The last table is
abbreviated here.) Note what step 5 shows: `repair` made the history agree with the edited file, but the
`price_cents` column in the database still has the old default. Repair records an opinion; it does not
change the schema. Real schema changes belong in a new `V` file.

## Transactions and failure handling (the honest version)

Each migration runs on its own connection with auto-commit off and is committed after its last statement.
That gives all-or-nothing behaviour only on databases with transactional DDL (PostgreSQL, for example).
H2 does not: every DDL statement (`CREATE`, `ALTER`, `DROP`) commits the open transaction. So if
a script is `CREATE TABLE b ...; INSERT ...; INSERT ... (fails)`, then on H2 the table `b` stays, while the
INSERT that ran before the failing one is rolled back. The tests assert exactly this.

To make that survivable the tool does this for every migration:

1. Insert the history row with `success = false` and commit it, before any statement runs.
2. Run the statements. On success, set `success = true` and `execution_ms` and commit.
3. On failure, roll back, store `execution_ms`, leave `success = false`, and stop. The error message names the
   file, the failing statement number and an excerpt of the statement.

Because the failed row is committed first, a half-applied script (or a process killed mid-way) is visible.
`migrate` and `validate` then refuse with a `FAILED_MIGRATION` issue until you fix the database by hand and run
`repair`, which deletes the failed row so the (edited) file is attempted again. The tool never tries to undo
half-applied DDL itself. On a transactional-DDL database the failed row is conservative: nothing needs cleaning
up, and `repair` just clears the flag.

## Safety details

- Lock: `INSERT` of a single row (id = 1) into `schema_history_lock`, committed immediately. The primary key
  makes a second inserter fail; it polls until `--lock-timeout-ms`, then throws a `LockException` that names the
  holder and says to use `unlock` if that process died. The lock is released in a `finally` block. A hard kill
  leaves the row behind, which is why `unlock` exists. Two threads are tested: one applies everything, the other
  waits and then finds nothing to do; and a waiter with a short timeout fails with a clear message.
- Checksum: SHA-256 over content after removing a BOM, normalising CRLF/CR to LF, stripping trailing whitespace
  on each line and trailing blank lines. Indentation, comments and interior blank lines still count.
- Validate on migrate: `migrate` runs the same checks as `validate` first and applies nothing if any fail.
- Out of order: a pending versioned file older than the latest applied version is an `OUT_OF_ORDER` issue unless
  `outOfOrder` is set, in which case it is applied.
- Splitting is done for all pending scripts before the first one runs, so an unterminated string in V5 does not
  leave V3 and V4 applied.

## SQL splitter

Handles `;` inside `'strings'` and `"identifiers"`, doubled quotes (`'it''s'`), `--` comments and `/* */`
comments, drops comment-only fragments, and rejects unterminated strings/comments with a line number.
Known limits: no dollar-quoting (`$$ ... $$`), no backslash escapes, no nested block comments, no Oracle `q'[ ]'`
literals, no `DELIMITER`/psql meta-commands, and no awareness of `BEGIN ... END` blocks containing `;`.
The tests pin the dollar-quote and nested-comment limits so they cannot change by accident.

## Mutation check

Passing tests prove little if they would also pass on broken code, so I applied 25 behaviour-defining
mutations one at a time (textual replacements run by `scripts/mutation_check.py`, which restores each file
afterwards), ran `mvn test`, and recorded whether any test failed. This is a hand-rolled check, not PIT, and the
mutations are the ones I thought of, so it says nothing about bugs I did not think of.

**Result: 22 of 25 killed, 3 survived.**

| # | Mutation | Result | First failing test(s) |
|---|---|---|---|
| M01 | versions sorted as text | killed | `MigrationLoaderTest.loadsVersionedThenRepeatableInNumericOrder`, 6 more |
| M02 | skip checksum compare | killed | `MigratorTest.editingAnAppliedFileIsChecksumDriftAndMigrateRefuses`, 3 more |
| M03 | no normalisation before hashing | killed | `ChecksumsTest.lineEndingStylesGiveTheSameChecksum`, 4 more |
| M04 | split lines on LF only | killed | `ChecksumsTest.lineEndingStylesGiveTheSameChecksum` (only this one; see below) |
| M05 | trailing whitespace not stripped | killed | `ChecksumsTest.trailingWhitespaceAndTrailingBlankLinesAreIgnored`, 2 more |
| M06 | BOM not stripped | killed | `ChecksumsTest.byteOrderMarkIsIgnored` |
| M07 | no lock (acquire returns at once) | killed | `MigratorTest.twoConcurrentMigratorsApplyEachMigrationExactlyOnce`, 2 more |
| M08 | lock never released | killed | 19 tests (everything that migrates twice hits the lock timeout) |
| M09 | release deletes the lock regardless of owner | **survived** | no test has a lock owned by someone else at release time |
| M10 | splitter ignores single quotes | killed | `SqlSplitterTest` and `MigratorTest` cases, 10 failing |
| M11 | splitter drops doubled-quote handling | **survived** | equivalent mutant, see below |
| M12 | splitter ignores `--` comments | killed | `SqlSplitterTest.apostropheInsideCommentDoesNotOpenAString`, 3 more |
| M13 | splitter ignores block comments | killed | `SqlSplitterTest.blockCommentHidesSemicolon`, 4 more |
| M14 | record success=true before running | killed | `MigratorTest.failedMigrationIsRecordedAsNotSuccessful`, 2 more |
| M15 | outOfOrder flag inverted | killed | `MigratorTest.pendingVersionOlderThanLatestAppliedIsOutOfOrder`, 2 more |
| M16 | duplicate detection disabled | killed | `MigrationLoaderTest.duplicateVersionsAreRejectedEvenWhenWrittenDifferently`, 1 more |
| M17 | migrate no longer refuses after a FAILED row | killed | `MigratorTest.migrateRefusesAfterAFailureUntilRepair` |
| M18 | repair does not delete failed rows | killed | `MigratorTest.repairThenFixedScriptCompletesTheRun` |
| M19 | baseline version itself not skipped | killed | `MigratorTest.baselineSkipsOlderFilesAndAppliesNewerOnes` |
| M20 | repeatable re-runs every time | killed | `MigratorTest.infoShowsStatesForAppliedAndPending`, 6 more |
| M21 | no rollback after a failed statement | killed | `MigratorTest.failureRollsBackDmlButDdlStaysBecauseH2AutoCommitsDdl` |
| M22 | gap detection disabled | killed | `MigrationLoaderTest.detectsGapsInMajorVersions`, 2 more |
| M23 | versioned migrations newest first | killed | 28 tests |
| M24 | repeatables before versioned | killed | 23 tests |
| M25 | `execution_ms` always stored as 0 | **survived** | no test asserts a positive duration |

Notes on the results:

- **M11 is an equivalent mutant.** In `'it''s; fine'`, treating the doubled quote as "close, then open again" produces
  the same statement boundaries as treating it as an escape. For splitting, the doubled-quote branch is redundant;
  it only matters if something later wants to interpret the string contents. I kept it because it is the correct
  reading of the syntax, not because a test needs it.
- **M09 is a real gap.** The `locked_by` condition on release is untested. Covering it needs a test where another
  owner's lock appears between acquire and release.
- **M25 is a real gap, accepted.** A duration is wall-clock time, and asserting `> 0` on a 1 ms migration would be
  flaky. The existing test only checks `>= 0`.
- **M04 was killed by one test only.** Splitting on `\n` alone still passes CRLF files, because the trailing-whitespace
  strip also removes the stray `\r`. Only the old-Mac CR-only case notices. That is a hint that the CR handling is
  partly redundant with the whitespace handling.
- M06 first reported `BROKEN` (pattern not found): my editor tooling had turned the `﻿` escape I typed into a
  literal invisible BOM character inside the Java source. I replaced it with `content.charAt(0) == 0xFEFF`, which
  has no invisible characters, and re-ran M06 (killed). The script reports `BROKEN` separately from survivors for
  exactly this reason.
- A bug found by the tests before any mutation: the duplicate check keyed on the version text, so `V1` and `V1.0`
  were not seen as duplicates. It now keys on `Version` equality.

Reproduce: `python scripts/mutation_check.py` (set `MVN` if `mvn` is not on PATH). Takes roughly 20 minutes.

## Limits / not verified

- Only H2 (2.3.232, in-memory) is tested. No PostgreSQL, MySQL, Oracle or SQL Server run. SQL types and the
  boolean/timestamp handling in the history table are plain JDBC but were never exercised elsewhere.
- No undo/rollback migrations, no multi-schema support, no placeholders/templating, no callbacks, no checksums
  for Java-based migrations, no concurrent use across JVMs tested (the lock is a table row, so it should work
  across processes, but the test uses two threads in one JVM).
- A hard-killed migrator leaves a stale lock row and possibly a `success = false` history row; both need a human.
- `repair` does not remove history rows whose file was deleted, and does not realign repeatable migrations.
- Gap detection looks at major versions only.
- The lock has no heartbeat or expiry. The tool is a learning project and has not been run against large or
  production schemas.
- CI has not been confirmed from this directory (the workflow is included; check the Actions tab).

## Layout

```
src/main/java/com/github/sup3rmar10/migrator/   Version, Checksums, SqlSplitter, MigrationLoader, Migrator, Main, Demo, ...
src/test/java/...                               unit and integration tests against unique in-memory H2 databases
migrations/                                     sample migrations (widgets and orders, synthetic)
scripts/mutation_check.py                       the mutation check described above
STEPS.md                                        how this was built, and how to run it yourself
```

MIT licensed.
