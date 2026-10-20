package com.github.sup3rmar10.migrator;

/**
 * One migration script. {@code version} is null for repeatable migrations, which are
 * identified by their description instead.
 */
public record Migration(Version version, String description, String script, String sql, String checksum) {

    public boolean isRepeatable() {
        return version == null;
    }

    public String label() {
        return isRepeatable() ? "R " + description : "V" + version + " " + description;
    }
}
