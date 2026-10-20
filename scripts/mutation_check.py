#!/usr/bin/env python
"""Hand-rolled mutation check: apply one textual mutation at a time, run the unit tests,
record whether at least one test failed, then restore the original file.

Usage:  python scripts/mutation_check.py            (all mutations)
        python scripts/mutation_check.py M05 M10    (selected ids)
Set MVN to the Maven executable if `mvn` is not on PATH.

Each mutation replaces the FIRST occurrence of `old` with `new`. A mutation whose `old` text
cannot be found is reported as BROKEN (the script is out of date), not as a survivor.
Files are always restored from the original bytes, even on Ctrl-C.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "github", "sup3rmar10", "migrator")
MVN = os.environ.get("MVN", "mvn")

MUTATIONS = [
    ("M01", "versions sorted as text", "Version.java",
     "public int compareTo(Version other) {",
     "public int compareTo(Version other) {\n        if (true) return toString().compareTo(other.toString());"),
    ("M02", "skip checksum compare (never report drift)", "Migrator.java",
     "} else if (!Objects.equals(m.checksum(), h.checksum())) {\n                    state = \"DRIFT\";",
     "} else if (false) {\n                    state = \"DRIFT\";"),
    ("M03", "do not normalise at all before hashing", "Checksums.java",
     "md.digest(normalize(content).getBytes", "md.digest(content.getBytes"),
    ("M04", "split lines only on LF (CR and CRLF not normalised)", "Checksums.java",
     "s.split(\"\\r\\n|\\r|\\n\", -1)", "s.split(\"\\n\", -1)"),
    ("M05", "do not strip trailing whitespace", "Checksums.java",
     "while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {",
     "while (end > 0 && false && Character.isWhitespace(line.charAt(end - 1))) {"),
    ("M06", "keep the BOM", "Checksums.java",
     "!content.isEmpty() && content.charAt(0) == 0xFEFF ?", "false ?"),
    ("M07", "no lock (acquire returns immediately)", "Migrator.java",
     "private String acquireLock(Connection c) throws SQLException {",
     "private String acquireLock(Connection c) throws SQLException {\n        if (true) return \"nolock\";"),
    ("M08", "lock never released", "Migrator.java",
     "\" WHERE id = 1 AND locked_by = ?\"", "\" WHERE id = 1 AND locked_by = ? AND 1 = 0\""),
    ("M09", "release deletes the lock even if another owner holds it", "Migrator.java",
     "\" WHERE id = 1 AND locked_by = ?\"", "\" WHERE id = 1 AND locked_by = ? OR 1 = 1\""),
    ("M10", "splitter ignores single quotes", "SqlSplitter.java",
     "if (c == '\\'' || c == '\"') {", "if (c == '\"') {"),
    ("M11", "splitter does not treat doubled quotes as an escape", "SqlSplitter.java",
     "if (i < n && sql.charAt(i) == c) {", "if (false) {"),
    ("M12", "splitter ignores -- comments", "SqlSplitter.java",
     "} else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {", "} else if (false) {"),
    ("M13", "splitter ignores block comments", "SqlSplitter.java",
     "} else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {", "} else if (false) {"),
    ("M14", "record success=true before running", "Migrator.java",
     "m.script(), m.checksum(), false);", "m.script(), m.checksum(), true);"),
    ("M15", "outOfOrder flag inverted", "Migrator.java",
     "if (cfg.outOfOrder()) {", "if (!cfg.outOfOrder()) {"),
    ("M16", "duplicate version detection disabled", "MigrationLoader.java",
     "if (other != null) {", "if (false) {"),
    ("M17", "migrate no longer refuses after a FAILED row", "Migrator.java",
     "if (!report.issues().isEmpty()) {\n                    throw new ValidationException(report.issues());",
     "if (report.issues().stream().anyMatch(i -> i.kind() != IssueKind.FAILED_MIGRATION)) {\n                    throw new ValidationException(report.issues());"),
    ("M18", "repair does not actually delete failed rows", "Migrator.java",
     "ps.setInt(1, h.rank());", "ps.setInt(1, -h.rank());"),
    ("M19", "baseline boundary: version == baseline is not skipped", "Migrator.java",
     "m.version().compareTo(baseline) <= 0", "m.version().compareTo(baseline) < 0"),
    ("M20", "repeatable re-runs on every migrate", "Migrator.java",
     "if (latest == null || !Objects.equals(latest.checksum(), m.checksum())) {", "if (true) {"),
    ("M21", "no rollback after a failed statement", "Migrator.java",
     "c.rollback();", "c.getAutoCommit();"),
    ("M22", "gap detection disabled", "MigrationLoader.java",
     "if (previous != null && major - previous > 1) {", "if (false) {"),
    ("M23", "versioned migrations ordered newest first", "MigrationLoader.java",
     "return a.version().compareTo(b.version());", "return b.version().compareTo(a.version());"),
    ("M24", "repeatables ordered before versioned", "MigrationLoader.java",
     "return a.isRepeatable() ? 1 : -1;", "return a.isRepeatable() ? -1 : 1;"),
    ("M25", "execution_ms always stored as 0", "Migrator.java",
     "ps.setInt(1, elapsedMs(started));", "ps.setInt(1, 0);"),
]


def run_tests():
    try:
        p = subprocess.run([MVN, "-B", "-q", "test"], cwd=ROOT, capture_output=True, text=True, timeout=600)
    except subprocess.TimeoutExpired:
        return "timeout", [], 0
    out = p.stdout + p.stderr
    if "COMPILATION ERROR" in out:
        return "compile-error", [], 0
    failing = sorted(set(re.findall(r"\[ERROR\]\s{2,}(\w+Test\.\w+)", out)))
    return ("killed" if p.returncode != 0 else "survived"), failing, len(failing)


def main():
    wanted = set(sys.argv[1:])
    originals = {}
    results = []
    try:
        for mid, desc, fname, old, new in MUTATIONS:
            if wanted and mid not in wanted:
                continue
            path = os.path.join(SRC, fname)
            if fname not in originals:
                with open(path, "rb") as f:
                    originals[fname] = f.read()
            text = originals[fname].decode("utf-8").replace("\r\n", "\n")
            if old not in text:
                results.append((mid, desc, "BROKEN (pattern not found)", []))
                print(f"{mid} {desc}: BROKEN", flush=True)
                continue
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text.replace(old, new, 1))
            status, failing, n = run_tests()
            with open(path, "wb") as f:
                f.write(originals[fname])
            label = {"killed": "KILLED", "survived": "SURVIVED", "compile-error": "COMPILE ERROR (invalid)",
                     "timeout": "KILLED (timeout)"}[status]
            results.append((mid, desc, label, failing))
            print(f"{mid} | {desc} | {label} | {n} failing: {', '.join(failing[:3])}", flush=True)
    finally:
        for fname, data in originals.items():
            with open(os.path.join(SRC, fname), "wb") as f:
                f.write(data)
    killed = sum(1 for r in results if r[2].startswith("KILLED"))
    print(f"\n{killed}/{len(results)} mutations killed")


if __name__ == "__main__":
    main()
