package com.github.sup3rmar10.migrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ChecksumsTest {

    @Test
    void isSha256OfNormalisedContent() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksums.checksum("abc"));
    }

    @Test
    void lineEndingStylesGiveTheSameChecksum() {
        String lf = "SELECT 1;\nSELECT 2;\n";
        assertEquals(Checksums.checksum(lf), Checksums.checksum("SELECT 1;\r\nSELECT 2;\r\n"));
        assertEquals(Checksums.checksum(lf), Checksums.checksum("SELECT 1;\rSELECT 2;\r"));
    }

    @Test
    void trailingWhitespaceAndTrailingBlankLinesAreIgnored() {
        String base = Checksums.checksum("SELECT 1;\nSELECT 2;");
        assertEquals(base, Checksums.checksum("SELECT 1;   \nSELECT 2;\t\n"));
        assertEquals(base, Checksums.checksum("SELECT 1;\nSELECT 2;\n\n\n"));
        assertEquals(base, Checksums.checksum("SELECT 1;\nSELECT 2;\n   \n"));
    }

    @Test
    void byteOrderMarkIsIgnored() {
        assertEquals(Checksums.checksum("SELECT 1;"), Checksums.checksum((char) 0xFEFF + "SELECT 1;"));
    }

    @Test
    void realContentChangesStillChangeTheChecksum() {
        String base = Checksums.checksum("SELECT 1;\nSELECT 2;");
        assertNotEquals(base, Checksums.checksum("SELECT 1;\nSELECT 3;"));
        assertNotEquals(base, Checksums.checksum("SELECT 1;\n  SELECT 2;"));
        assertNotEquals(base, Checksums.checksum("SELECT 1;\n\nSELECT 2;"));
        assertNotEquals(base, Checksums.checksum("-- note\nSELECT 1;\nSELECT 2;"));
    }

    @Test
    void normalizeProducesLfOnlyWithoutTrailingNewline() {
        assertEquals("a\nb", Checksums.normalize("a  \r\nb \r\n\r\n"));
    }
}
