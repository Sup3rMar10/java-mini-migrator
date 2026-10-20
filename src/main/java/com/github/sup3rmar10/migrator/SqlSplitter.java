package com.github.sup3rmar10.migrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into statements on semicolons. A small hand-written scanner, not a
 * SQL parser.
 *
 * <p>Handled: ';' inside 'string literals' and "quoted identifiers", doubled quotes ('it''s'),
 * -- line comments, and block comments. Comments are kept inside the statement text (the
 * database ignores them); a fragment that contains only comments or whitespace is dropped.
 *
 * <p>Known limits (not handled): dollar-quoted bodies ($$ ... $$), backslash escapes inside
 * strings (MySQL style), nested block comments, Oracle q'[...]' literals, client commands
 * such as DELIMITER or psql backslash commands, and BEGIN ... END blocks that contain
 * semicolons. Unterminated strings and comments are rejected rather than guessed at.
 */
public final class SqlSplitter {
    private SqlSplitter() {
    }

    public static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean hasContent = false;
        int line = 1;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int startLine = line;
                hasContent = true;
                cur.append(c);
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = sql.charAt(i);
                    cur.append(d);
                    i++;
                    if (d == '\n') {
                        line++;
                    }
                    if (d == c) {
                        if (i < n && sql.charAt(i) == c) {
                            cur.append(c);
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) {
                    throw new MigrationException("Unterminated " + (c == '\'' ? "string literal" : "quoted identifier")
                            + " starting at line " + startLine);
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    cur.append(sql.charAt(i));
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int startLine = line;
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new MigrationException("Unterminated block comment starting at line " + startLine);
                }
                String comment = sql.substring(i, end + 2);
                for (int k = 0; k < comment.length(); k++) {
                    if (comment.charAt(k) == '\n') {
                        line++;
                    }
                }
                cur.append(comment);
                i = end + 2;
            } else if (c == ';') {
                flush(statements, cur, hasContent);
                hasContent = false;
                i++;
            } else {
                if (!Character.isWhitespace(c)) {
                    hasContent = true;
                }
                if (c == '\n') {
                    line++;
                }
                cur.append(c);
                i++;
            }
        }
        flush(statements, cur, hasContent);
        return statements;
    }

    private static void flush(List<String> statements, StringBuilder cur, boolean hasContent) {
        String text = cur.toString().trim();
        if (hasContent && !text.isEmpty()) {
            statements.add(text);
        }
        cur.setLength(0);
    }
}
