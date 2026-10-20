package com.github.sup3rmar10.migrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Shared helpers: unique in-memory H2 databases, migration files, and tiny query helpers. */
final class Fixtures {
    private Fixtures() {
    }

    static String url() {
        return "jdbc:h2:mem:t" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    }

    static Path write(Path dir, String name, String content) {
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve(name);
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** V1 widgets, V2 price column, R view. */
    static void writeBasic(Path dir) {
        write(dir, "V1__create_widgets.sql", "CREATE TABLE widgets (id INT PRIMARY KEY, name VARCHAR(50));\n");
        write(dir, "V2__add_price.sql", "ALTER TABLE widgets ADD COLUMN price INT;\n");
        write(dir, "R__widget_names.sql", "CREATE OR REPLACE VIEW widget_names AS SELECT name FROM widgets;\n");
    }

    static Migrator migrator(String url, Path dir) {
        return migrator(url, dir, c -> { });
    }

    static Migrator migrator(String url, Path dir, Consumer<MigratorConfig> tweak) {
        MigratorConfig cfg = new MigratorConfig().url(url).lockTimeoutMs(5_000);
        tweak.accept(cfg);
        return Migrator.forDirectory(cfg, dir);
    }

    static List<String> column(String url, String sql) {
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static long count(String url, String sql) {
        return Long.parseLong(column(url, sql).get(0));
    }

    static void exec(String url, String sql) {
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean tableExists(String url, String upperCaseName) {
        return count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '" + upperCaseName + "'") > 0;
    }

    static List<String> states(Migrator m) {
        return m.info().stream().map(Migrator.InfoRow::state).toList();
    }
}
