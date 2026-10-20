package com.github.sup3rmar10.migrator;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * A dotted numeric version such as 1, 1.9 or 1.10. Segments compare as numbers, so
 * 1.10 is after 1.9, and missing trailing segments count as zero (1 equals 1.0).
 * Underscores are accepted as separators (V1_2 means 1.2) like Flyway does.
 */
public final class Version implements Comparable<Version> {
    private static final Pattern FORMAT = Pattern.compile("\\d+(?:[._]\\d+)*");

    private final long[] parts;

    private Version(long[] parts) {
        this.parts = parts;
    }

    public static Version parse(String text) {
        if (text == null || !FORMAT.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid version '" + text + "': expected digits separated by '.' or '_'");
        }
        String[] segments = text.split("[._]");
        long[] parts = new long[segments.length];
        for (int i = 0; i < segments.length; i++) {
            parts[i] = Long.parseLong(segments[i]);
        }
        return new Version(parts);
    }

    public long major() {
        return parts[0];
    }

    @Override
    public int compareTo(Version other) {
        int n = Math.max(parts.length, other.parts.length);
        for (int i = 0; i < n; i++) {
            long a = i < parts.length ? parts[i] : 0;
            long b = i < other.parts.length ? other.parts[i] : 0;
            if (a != b) {
                return Long.compare(a, b);
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        int end = parts.length;
        while (end > 1 && parts[end - 1] == 0) {
            end--;
        }
        return Arrays.hashCode(Arrays.copyOf(parts, end));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
