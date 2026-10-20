package com.github.sup3rmar10.migrator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Checksum of a migration file. Content is normalised first so that changes which do not
 * alter the meaning of the file for a human editor are not reported as drift: a BOM, CRLF
 * versus LF versus CR line endings, trailing whitespace on a line, and trailing blank
 * lines. Everything else (indentation, comments, interior blank lines) still counts.
 */
public final class Checksums {
    private Checksums() {
    }

    public static String normalize(String content) {
        String s = !content.isEmpty() && content.charAt(0) == 0xFEFF ? content.substring(1) : content;
        String[] lines = s.split("\r\n|\r|\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            int end = line.length();
            while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
                end--;
            }
            out.append(line, 0, end);
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        out.setLength(end);
        return out.toString();
    }

    public static String checksum(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalize(content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java spec", e);
        }
    }
}
