package com.github.sup3rmar10.migrator;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads migration files from a directory or a classpath location. Files named
 * {@code V<version>__<description>.sql} are versioned, {@code R__<description>.sql} are
 * repeatable. Any other *.sql file is an error (a typo should not be silently skipped);
 * files with other extensions are ignored. Only the top level of the location is read.
 */
public final class MigrationLoader {
    private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:[._]\\d+)*)__(.+)\\.sql");
    private static final Pattern REPEATABLE = Pattern.compile("R__(.+)\\.sql");

    /** Versioned migrations by version, then repeatable ones by description. */
    public static final Comparator<Migration> ORDER = (a, b) -> {
        if (a.isRepeatable() != b.isRepeatable()) {
            return a.isRepeatable() ? 1 : -1;
        }
        if (a.isRepeatable()) {
            return a.description().compareTo(b.description());
        }
        return a.version().compareTo(b.version());
    };

    private MigrationLoader() {
    }

    public static List<Migration> fromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new MigrationException("Migration directory not found: " + dir);
        }
        List<Migration> found = new ArrayList<>();
        collectDirectory(dir, found);
        return finish(found);
    }

    public static List<Migration> fromClasspath(ClassLoader loader, String location) {
        String prefix = location.replaceAll("^/+|/+$", "");
        List<Migration> found = new ArrayList<>();
        boolean any = false;
        try {
            Enumeration<URL> urls = loader.getResources(prefix);
            while (urls.hasMoreElements()) {
                any = true;
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    collectDirectory(Path.of(url.toURI()), found);
                } else if ("jar".equals(url.getProtocol())) {
                    collectJar(url, prefix, found);
                } else {
                    throw new MigrationException("Unsupported classpath resource protocol: " + url.getProtocol());
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new MigrationException("Cannot read classpath location " + location + ": " + e.getMessage(), e);
        }
        if (!any) {
            throw new MigrationException("Classpath location not found: " + location);
        }
        return finish(found);
    }

    /** Builds a migration from a file name and its content; rejects names that fit neither pattern. */
    public static Migration parse(String filename, String content) {
        Matcher v = VERSIONED.matcher(filename);
        if (v.matches()) {
            return new Migration(Version.parse(v.group(1)), describe(v.group(2)), filename, content, Checksums.checksum(content));
        }
        Matcher r = REPEATABLE.matcher(filename);
        if (r.matches()) {
            return new Migration(null, describe(r.group(1)), filename, content, Checksums.checksum(content));
        }
        throw new MigrationException("Invalid migration file name '" + filename
                + "': expected V<version>__<description>.sql or R__<description>.sql");
    }

    /**
     * Gaps in the major version sequence (V1, V3 but no V2.x). Minor versions are not
     * checked: V1 then V1.5 is normal. The first major version is not required to be 1.
     */
    public static List<String> findGaps(List<Migration> migrations) {
        TreeSet<Long> majors = new TreeSet<>();
        for (Migration m : migrations) {
            if (!m.isRepeatable()) {
                majors.add(m.version().major());
            }
        }
        List<String> gaps = new ArrayList<>();
        Long previous = null;
        for (long major : majors) {
            if (previous != null && major - previous > 1) {
                gaps.add("Gap in versions: no migration for major version " + (previous + 1)
                        + (major - previous > 2 ? " to " + (major - 1) : "")
                        + " (found " + previous + " then " + major + ")");
            }
            previous = major;
        }
        return gaps;
    }

    private static void collectDirectory(Path dir, List<Migration> out) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.sorted()::iterator) {
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && name.endsWith(".sql")) {
                    out.add(parse(name, Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            throw new MigrationException("Cannot read migrations from " + dir + ": " + e, e);
        }
    }

    private static void collectJar(URL url, String prefix, List<Migration> out) throws IOException {
        URLConnection conn = url.openConnection();
        conn.setUseCaches(false);
        try (JarFile jar = ((JarURLConnection) conn).getJarFile()) {
            String dirPrefix = prefix + "/";
            List<JarEntry> entries = new ArrayList<>();
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(dirPrefix)
                        && name.indexOf('/', dirPrefix.length()) < 0 && name.endsWith(".sql")) {
                    entries.add(entry);
                }
            }
            entries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry entry : entries) {
                try (InputStream in = jar.getInputStream(entry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    out.add(parse(entry.getName().substring(dirPrefix.length()), content));
                }
            }
        }
    }

    private static List<Migration> finish(List<Migration> found) {
        // Keyed by Version (not its text) so that V1 and V1.0 count as the same version.
        Map<Object, Migration> seen = new HashMap<>();
        for (Migration m : found) {
            Object key = m.isRepeatable() ? m.description() : m.version();
            Migration other = seen.put(key, m);
            if (other != null) {
                throw new MigrationException("Duplicate migration " + (m.isRepeatable()
                        ? "description '" + m.description() + "'" : "version " + m.version())
                        + ": " + other.script() + " and " + m.script());
            }
        }
        List<Migration> sorted = new ArrayList<>(found);
        sorted.sort(ORDER);
        return sorted;
    }

    private static String describe(String raw) {
        return raw.replace('_', ' ');
    }
}
