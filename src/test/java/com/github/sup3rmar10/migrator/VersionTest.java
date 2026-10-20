package com.github.sup3rmar10.migrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class VersionTest {

    private static Version v(String s) {
        return Version.parse(s);
    }

    @Test
    void segmentsCompareNumericallyNotAsText() {
        assertTrue(v("1.10").compareTo(v("1.9")) > 0);
        assertTrue(v("1.2").compareTo(v("1.10")) < 0);
        assertTrue(v("10").compareTo(v("9")) > 0);
        assertTrue(v("2").compareTo(v("1.99")) > 0);
    }

    @Test
    void missingTrailingSegmentsCountAsZero() {
        assertEquals(v("1"), v("1.0"));
        assertEquals(v("1").hashCode(), v("1.0.0").hashCode());
        assertTrue(v("1.0.1").compareTo(v("1")) > 0);
    }

    @Test
    void underscoreIsASeparatorAndLeadingZerosAreIgnored() {
        assertEquals(v("1.2"), v("1_2"));
        assertEquals("1.2", v("01_02").toString());
    }

    @Test
    void invalidVersionsAreRejected() {
        for (String bad : new String[] {"", "a", "1.", ".1", "1..2", "1.x", "-1", "1 2"}) {
            assertThrows(IllegalArgumentException.class, () -> Version.parse(bad), bad);
        }
    }

    @Test
    void sortingAListGivesNumericOrder() {
        List<Version> versions = new ArrayList<>(Stream.of("1.10", "1.9", "1.2", "1", "2", "1.1").map(VersionTest::v).toList());
        Collections.sort(versions);
        assertEquals("[1, 1.1, 1.2, 1.9, 1.10, 2]", versions.toString());
    }
}
