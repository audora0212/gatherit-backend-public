package compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class CompatibilitySupport {
    static final String DATABASE = "gatherit_compat_check";
    static final String USER = "compat";
    static final String PASSWORD = "compat-fixture-only";
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private CompatibilitySupport() {}

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static void writeJson(Path target, Object value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), value);
    }

    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }

    static java.util.Set<String> sqlModes(String modes) {
        return java.util.Arrays.stream(modes.split(",")).map(String::trim).filter(mode -> !mode.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    static Map<String, String> jdbcSessionSettings(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet values = statement.executeQuery(
                "SELECT @@GLOBAL.sql_mode,@@SESSION.sql_mode,@@SESSION.time_zone")) {
            require(values.next(), "JDBC session settings unavailable");
            Map<String, String> settings = new TreeMap<>();
            settings.put("globalSqlMode", values.getString(1));
            settings.put("jdbcSessionSqlMode", values.getString(2));
            settings.put("jdbcSessionTimeZone", values.getString(3));
            require(sqlModes(values.getString(1)).equals(java.util.Set.of("NO_ENGINE_SUBSTITUTION")),
                    "Fixture server global SQL mode is unexpected");
            // Both real Connector/J 8.3 and 9.7 enable STRICT_TRANS_TABLES for
            // jdbcCompliantTruncation. Do not confuse this with the CLI session.
            require(sqlModes(values.getString(2)).equals(java.util.Set.of("NO_ENGINE_SUBSTITUTION", "STRICT_TRANS_TABLES")),
                    "JDBC session SQL mode differs from the legacy/candidate driver contract");
            require(java.util.Set.of("UTC", "+00:00").contains(values.getString(3)),
                    "Fixture JDBC session time zone must use UTC");
            return settings;
        }
    }

    static List<String> tableNames(Connection connection) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            while (rows.next()) {
                String name = rows.getString(1);
                require(name.matches("[a-z_]+"), "Unexpected fixture table identifier");
                names.add(name);
            }
        }
        return names;
    }

    static Map<String, String> schemaDescription(Connection connection) throws Exception {
        Map<String, String> definitions = new TreeMap<>();
        for (String table : tableNames(connection)) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                require(rows.next(), "Missing fixture table definition");
                definitions.put(table, rows.getString(2).replaceAll(" AUTO_INCREMENT=\\d+", ""));
            }
        }
        return definitions;
    }

    static String schemaFingerprint(Connection connection) throws Exception {
        return sha256(JSON.writeValueAsBytes(schemaDescription(connection)));
    }

    static String dataFingerprint(Connection connection) throws Exception {
        Map<String, List<String>> tables = new TreeMap<>();
        for (String table : tableNames(connection)) {
            List<String> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM `" + table + "`")) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) values.add(result.getString(i));
                    rows.add(JSON.writeValueAsString(values));
                }
            }
            rows.sort(String::compareTo);
            tables.put(table, rows);
        }
        return sha256(JSON.writeValueAsBytes(tables));
    }

    static List<String> changedTables(Map<String, String> before, Map<String, String> after) {
        var names = new java.util.TreeSet<String>();
        names.addAll(before.keySet()); names.addAll(after.keySet());
        return names.stream().filter(name -> !java.util.Objects.equals(before.get(name), after.get(name))).toList();
    }
}
