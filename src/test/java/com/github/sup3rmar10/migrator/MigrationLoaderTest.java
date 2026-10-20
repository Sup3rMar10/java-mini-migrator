package com.github.sup3rmar10.migrator;

import static com.github.sup3rmar10.migrator.Fixtures.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationLoaderTest {

    private static List<String> scripts(List<Migration> migrations) {
        return migrations.stream().map(Migration::script).toList();
    }

    @Test
    void loadsVersionedThenRepeatableInNumericOrder(@TempDir Path dir) {
        write(dir, "V1.10__ten.sql", "SELECT 10;");
        write(dir, "V1.9__nine.sql", "SELECT 9;");
        write(dir, "R__b_view.sql", "SELECT 1;");
        write(dir, "R__a_view.sql", "SELECT 1;");
        write(dir, "V1.2__two.sql", "SELECT 2;");
        assertEquals(List.of("V1.2__two.sql", "V1.9__nine.sql", "V1.10__ten.sql", "R__a_view.sql", "R__b_view.sql"),
                scripts(MigrationLoader.fromDirectory(dir)));
    }

    @Test
    void parsesVersionDescriptionAndChecksum(@TempDir Path dir) {
        write(dir, "V3_1__add_order_note.sql", "ALTER TABLE orders ADD COLUMN note INT;\r\n");
        Migration m = MigrationLoader.fromDirectory(dir).get(0);
        assertEquals(Version.parse("3.1"), m.version());
        assertEquals("add order note", m.description());
        assertEquals(Checksums.checksum("ALTER TABLE orders ADD COLUMN note INT;\n"), m.checksum());
        assertFalse(m.isRepeatable());
    }

    @Test
    void ignoresFilesThatAreNotSql(@TempDir Path dir) {
        write(dir, "V1__a.sql", "SELECT 1;");
        write(dir, "README.md", "hello");
        write(dir, "notes.txt", "hello");
        assertEquals(1, MigrationLoader.fromDirectory(dir).size());
    }

    @Test
    void duplicateVersionsAreRejectedEvenWhenWrittenDifferently(@TempDir Path dir) {
        write(dir, "V1__a.sql", "SELECT 1;");
        write(dir, "V1.0__b.sql", "SELECT 2;");
        MigrationException e = assertThrows(MigrationException.class, () -> MigrationLoader.fromDirectory(dir));
        assertTrue(e.getMessage().contains("Duplicate migration version 1"), e.getMessage());
        assertTrue(e.getMessage().contains("V1__a.sql") && e.getMessage().contains("V1.0__b.sql"), e.getMessage());
    }

    @Test
    void duplicateRepeatableDescriptionsAreRejected(@TempDir Path dir) {
        write(dir, "R__view_one.sql", "SELECT 1;");
        write(dir, "R__view one.sql", "SELECT 1;");
        assertThrows(MigrationException.class, () -> MigrationLoader.fromDirectory(dir));
    }

    @Test
    void sqlFileWithAnInvalidNameIsAnError(@TempDir Path dir) {
        write(dir, "v1__lowercase.sql", "SELECT 1;");
        MigrationException e = assertThrows(MigrationException.class, () -> MigrationLoader.fromDirectory(dir));
        assertTrue(e.getMessage().contains("Invalid migration file name 'v1__lowercase.sql'"), e.getMessage());
    }

    @Test
    void singleUnderscoreAfterVersionIsAnInvalidName(@TempDir Path dir) {
        write(dir, "V1_description.sql", "SELECT 1;");
        assertThrows(MigrationException.class, () -> MigrationLoader.fromDirectory(dir));
    }

    @Test
    void missingDirectoryIsAnError(@TempDir Path dir) {
        assertThrows(MigrationException.class, () -> MigrationLoader.fromDirectory(dir.resolve("nope")));
    }

    @Test
    void detectsGapsInMajorVersions(@TempDir Path dir) {
        write(dir, "V1__a.sql", "SELECT 1;");
        write(dir, "V1.5__b.sql", "SELECT 1;");
        write(dir, "V3__c.sql", "SELECT 1;");
        write(dir, "V6__d.sql", "SELECT 1;");
        List<String> gaps = MigrationLoader.findGaps(MigrationLoader.fromDirectory(dir));
        assertEquals(2, gaps.size());
        assertTrue(gaps.get(0).contains("major version 2"), gaps.get(0));
        assertTrue(gaps.get(1).contains("major version 4 to 5"), gaps.get(1));
    }

    @Test
    void noGapsForDottedMinorsOrRepeatables(@TempDir Path dir) {
        write(dir, "V1__a.sql", "SELECT 1;");
        write(dir, "V1.10__b.sql", "SELECT 1;");
        write(dir, "V2__c.sql", "SELECT 1;");
        write(dir, "R__x.sql", "SELECT 1;");
        assertTrue(MigrationLoader.findGaps(MigrationLoader.fromDirectory(dir)).isEmpty());
    }

    @Test
    void loadsFromAClasspathDirectory() {
        List<Migration> found = MigrationLoader.fromClasspath(getClass().getClassLoader(), "db/sample");
        assertEquals(List.of("V1__create_widgets.sql", "V2__create_gadgets.sql"), scripts(found));
    }

    @Test
    void loadsFromAJarOnTheClasspathAndIgnoresSubfolders(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("migrations.jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os)) {
            add(out, "db/", null);
            add(out, "db/migrations/", null);
            add(out, "db/migrations/V1__a.sql", "SELECT 1;");
            add(out, "db/migrations/R__v.sql", "SELECT 2;");
            add(out, "db/migrations/sub/V9__nested.sql", "SELECT 9;");
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertEquals(List.of("V1__a.sql", "R__v.sql"), scripts(MigrationLoader.fromClasspath(loader, "/db/migrations/")));
            assertThrows(MigrationException.class, () -> MigrationLoader.fromClasspath(loader, "db/absent"));
        }
    }

    private static void add(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        if (content != null) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        out.closeEntry();
    }
}
