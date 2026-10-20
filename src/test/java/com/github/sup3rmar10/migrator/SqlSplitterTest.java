package com.github.sup3rmar10.migrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlSplitterTest {

    @Test
    void splitsOnSemicolons() {
        assertEquals(List.of("CREATE TABLE a (id INT)", "INSERT INTO a VALUES (1)"),
                SqlSplitter.split("CREATE TABLE a (id INT);\nINSERT INTO a VALUES (1);\n"));
    }

    @Test
    void lastStatementWithoutSemicolonIsKept() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), SqlSplitter.split("SELECT 1; SELECT 2"));
    }

    @Test
    void semicolonInsideStringLiteralDoesNotSplit() {
        assertEquals(List.of("INSERT INTO t VALUES ('a;b')", "SELECT 1"),
                SqlSplitter.split("INSERT INTO t VALUES ('a;b'); SELECT 1;"));
    }

    @Test
    void doubledQuoteInsideStringIsAnEscape() {
        List<String> parts = SqlSplitter.split("INSERT INTO t VALUES ('it''s; fine'); SELECT 2;");
        assertEquals(List.of("INSERT INTO t VALUES ('it''s; fine')", "SELECT 2"), parts);
    }

    @Test
    void stringThatIsOnlyAnEscapedQuote() {
        assertEquals(List.of("SELECT ''''", "SELECT 2"), SqlSplitter.split("SELECT ''''; SELECT 2"));
    }

    @Test
    void emptyStringLiteral() {
        assertEquals(List.of("SELECT '' || 'a;b'"), SqlSplitter.split("SELECT '' || 'a;b';"));
    }

    @Test
    void semicolonInsideQuotedIdentifier() {
        assertEquals(List.of("CREATE TABLE \"a;b\" (id INT)", "SELECT 1"),
                SqlSplitter.split("CREATE TABLE \"a;b\" (id INT); SELECT 1;"));
    }

    @Test
    void doubledDoubleQuoteInIdentifier() {
        assertEquals(List.of("CREATE TABLE \"a\"\";b\" (id INT)"), SqlSplitter.split("CREATE TABLE \"a\"\";b\" (id INT);"));
    }

    @Test
    void lineCommentHidesSemicolon() {
        assertEquals(List.of("SELECT 1 -- not; a split\n, 2"), SqlSplitter.split("SELECT 1 -- not; a split\n, 2;"));
    }

    @Test
    void blockCommentHidesSemicolon() {
        assertEquals(List.of("SELECT /* a; b */ 1"), SqlSplitter.split("SELECT /* a; b */ 1;"));
    }

    @Test
    void multiLineBlockComment() {
        assertEquals(List.of("/* header;\nmore; */\nSELECT 1"), SqlSplitter.split("/* header;\nmore; */\nSELECT 1;"));
    }

    @Test
    void dashesInsideStringAreNotAComment() {
        assertEquals(List.of("SELECT '-- x'", "SELECT 2"), SqlSplitter.split("SELECT '-- x'; SELECT 2;"));
    }

    @Test
    void blockCommentMarkerInsideStringIsNotAComment() {
        assertEquals(List.of("SELECT '/* x'", "SELECT 2", "SELECT 3"),
                SqlSplitter.split("SELECT '/* x'; SELECT 2; SELECT 3;"));
    }

    @Test
    void apostropheInsideCommentDoesNotOpenAString() {
        assertEquals(List.of("-- don't panic\nSELECT 1", "SELECT 2"), SqlSplitter.split("-- don't panic\nSELECT 1; SELECT 2;"));
    }

    @Test
    void commentOnlyFragmentsAreDropped() {
        assertEquals(List.of("SELECT 1"), SqlSplitter.split("SELECT 1;\n-- trailing note\n/* another */\n"));
    }

    @Test
    void emptyAndBlankInputGiveNoStatements() {
        assertTrue(SqlSplitter.split("").isEmpty());
        assertTrue(SqlSplitter.split("  \n\t ; ;; \n").isEmpty());
    }

    @Test
    void crlfInputIsHandled() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), SqlSplitter.split("SELECT 1;\r\nSELECT 2;\r\n"));
    }

    @Test
    void statementsKeepTheirInnerNewlines() {
        assertEquals(List.of("CREATE TABLE t (\n  id INT\n)"), SqlSplitter.split("CREATE TABLE t (\n  id INT\n);"));
    }

    @Test
    void unterminatedStringIsRejectedWithLineNumber() {
        MigrationException e = assertThrows(MigrationException.class, () -> SqlSplitter.split("SELECT 1;\nSELECT 'oops;\nSELECT 3;"));
        assertTrue(e.getMessage().contains("Unterminated string literal"), e.getMessage());
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void unterminatedBlockCommentIsRejected() {
        MigrationException e = assertThrows(MigrationException.class, () -> SqlSplitter.split("SELECT 1; /* never closed"));
        assertTrue(e.getMessage().contains("Unterminated block comment"), e.getMessage());
    }

    @Test
    void knownLimitDollarQuotingIsNotSupported() {
        // Documented limit: the body of a $$ block is split like ordinary SQL.
        List<String> parts = SqlSplitter.split("CREATE ALIAS f AS $$ int a() { return 1; } $$;");
        assertEquals(2, parts.size());
    }

    @Test
    void knownLimitNestedBlockCommentsAreNotSupported() {
        // The comment ends at the first */ even though a nested /* was opened.
        List<String> parts = SqlSplitter.split("/* a /* b */ c; */ SELECT 1;");
        assertEquals(List.of("/* a /* b */ c", "*/ SELECT 1"), parts);
    }
}
