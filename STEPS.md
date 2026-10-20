# STEPS

## Part A: Steps taken (in order)

1. **Scope.** Decided what a Flyway-style tool minimally needs: V/R files, numeric version ordering, a history table
   with checksums, a lock, a statement splitter, and honest failure handling. Decided against undo migrations,
   placeholders and multi-schema support.
2. **Project skeleton.** `pom.xml` (Java 17 release, JUnit 5.11.4, surefire 3.5.2, H2 2.3.232 as runtime scope),
   `.gitattributes` (`* text=auto eol=lf`), `.gitignore`, MIT license, CI workflow. Added `maven-dependency-plugin`
   `copy-dependencies` bound to `package` so `target/lib/` holds the H2 jar; without it `java -cp target/classes ...`
   cannot find a JDBC driver.
3. **Sample migrations.** Synthetic widgets/orders SQL. `V1.10__seed_widgets.sql` deliberately depends on the `sku`
   column from `V1.2`, so a text-sorted run fails.
4. **Small pure pieces first.** `Version` (numeric segments), `Checksums` (normalise then SHA-256), `SqlSplitter` (one-pass
   scanner), `MigrationLoader` (directory, classpath directory, classpath jar; duplicate and gap detection).
5. **Migrator.** One `analyze(local, history)` function feeds info, validate, dryRun and migrate. Lock on its own
   autocommit connection; each migration on a separate connection. Failure protocol: commit a `success=false` row first,
   flip it to true at the end.
6. **CLI and demo.** `Main` with exit codes 0/1/2; `Demo` copies the sample directory to a temp dir so editing an applied
   file never touches the shipped files.
7. **Tooling bug 1: shell heredocs.** The first attempt to create all Java files in one shell heredoc call failed to
   parse and wrote nothing. Switched to writing files with the editor tool.
8. **Compiled and ran the demo** before writing tests. It worked first time; the output is in the README.
9. **Tests.** Fixtures (unique `jdbc:h2:mem:<uuid>;DB_CLOSE_DELAY=-1` per test, `@TempDir` for files), then splitter, version,
   checksum, loader, migrator and CLI tests. Two-thread lock test: thread A sleeps inside its first migration, thread B starts
   after A is inside the lock; expected result is A applies all, B applies nothing. Second lock test: B has a 200 ms
   timeout and must fail with a message naming the holder.
10. **Bug 2 (found by a test): `V1` and `V1.0` were not duplicates.** The loader keyed duplicates on the version text, but
    `Version` treats them as equal. Fixed by keying on `Version` itself. `mvn -q -B verify` was green twice afterwards.
11. **Mutation check.** Wrote `scripts/mutation_check.py` (25 textual mutations, restore after each), ran it in the
    background: 21 killed, 3 survived, 1 `BROKEN`.
12. **Tooling bug 2: invisible BOM.** M06 was `BROKEN` because the editor tooling had converted my `﻿` escape into a
    literal BOM character in `Checksums.java`. Replaced with `content.charAt(0) == 0xFEFF` (also fixed the same thing in
    `ChecksumsTest`), re-ran M06: killed. Final: 22 of 25 killed. Survivors: M09 (lock owner check on release, untested),
    M11 (equivalent mutant: doubled-quote handling is redundant for splitting), M25 (execution_ms, untestable without
    flakiness).
13. **Final checks.** Confirmed the mutation script restored every source file (the full suite was re-run), ran
    `mvn -q -B verify` twice more, and re-ran the demo from `target/classes` + `target/lib`.
14. **Docs.** README (commands, demo output, transactions section, limits), this file. CI status deliberately not claimed.

## Part B: How to run it yourself

Requirements: JDK 17 or newer, Maven 3.9.

```bash
git clone <this repo> && cd java-mini-migrator
mvn -q -B verify                     # runs the tests, copies the H2 jar to target/lib
```

Run the scripted demo (use `;` instead of `:` in the classpath on Windows cmd/PowerShell):

```bash
java -cp "target/classes:target/lib/*" com.github.sup3rmar10.migrator.Main demo --dir ./migrations
```

Use it on a real (file) H2 database:

```bash
URL="jdbc:h2:file:./target/play-db"
CP="target/classes:target/lib/*"
java -cp "$CP" com.github.sup3rmar10.migrator.Main dryRun   --url "$URL" --dir ./migrations
java -cp "$CP" com.github.sup3rmar10.migrator.Main migrate  --url "$URL" --dir ./migrations
java -cp "$CP" com.github.sup3rmar10.migrator.Main info     --url "$URL" --dir ./migrations
# edit an applied file in ./migrations (copy the directory first if you want to keep the sample clean), then:
java -cp "$CP" com.github.sup3rmar10.migrator.Main validate --url "$URL" --dir ./migrations   # exit code 1
java -cp "$CP" com.github.sup3rmar10.migrator.Main repair   --url "$URL" --dir ./migrations
```

Try a failure: add `V3__bad.sql` containing `CREATE TABLE t (id INT PRIMARY KEY); INSERT INTO t VALUES (1); INSERT INTO t VALUES (1);`,
run `migrate` (it fails at statement 3), run `migrate` again (refused, asks for repair), run `info` (state FAILED), then
`DROP TABLE t`, fix the file, `repair`, `migrate`.

Run the mutation check (about 20 minutes): `python scripts/mutation_check.py` (set `MVN` to the Maven executable if needed).
