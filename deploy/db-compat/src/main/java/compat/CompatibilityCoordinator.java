package compat;

import org.testcontainers.containers.MySQLContainer;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Docker is used only by an explicitly enabled CI job, never by a developer smoke test. */
public final class CompatibilityCoordinator {
    private final Path schemaFile;
    private final Path reports;
    private final Path privateFiles;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> phases = new ArrayList<>();

    private CompatibilityCoordinator(Path schemaFile, Path reports) throws Exception {
        this.schemaFile = schemaFile.toAbsolutePath(); this.reports = reports.toAbsolutePath();
        privateFiles = this.reports.getParent().resolve("private");
        Files.createDirectories(privateFiles); Files.createDirectories(this.reports.resolve("phases"));
    }

    public static void main(String[] args) throws Exception {
        CompatibilitySupport.require("true".equals(System.getenv("GITHUB_ACTIONS"))
                        && "true".equals(System.getenv("GATHERIT_RUN_DB_COMPAT")),
                "MySQL compatibility runs only in explicitly enabled GitHub CI; local Docker is forbidden");
        CompatibilitySupport.require(args.length == 2, "Expected schema and report paths");
        String expectedJava = System.getenv("DB_COMPAT_EXPECT_JAVA");
        if (expectedJava != null) CompatibilitySupport.require(
                Runtime.version().feature() == Integer.parseInt(expectedJava), "Unexpected Java runtime");
        new CompatibilityCoordinator(Path.of(args[0]), Path.of(args[1])).run();
    }

    private void run() throws Exception {
        report.put("startedAt", Instant.now().toString());
        report.put("java", System.getProperty("java.version"));
        report.put("jvmTimezone", java.util.TimeZone.getDefault().getID());
        report.put("legacyHibernate", "6.5.3.Final"); report.put("candidateHibernate", "7.4.13.Final");
        report.put("sourceSchemaSha256", CompatibilitySupport.sha256(Files.readAllBytes(schemaFile)));
        report.put("fixtureOnly", true); report.put("productionRowsCopied", 0);
        report.put("fixtureTls", "JDBC TLS is disabled for the loopback-only fixture.");
        report.put("phases", phases);
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.9")
                .withDatabaseName(CompatibilitySupport.DATABASE)
                .withUsername(CompatibilitySupport.USER).withPassword(CompatibilitySupport.PASSWORD)
                .withEnv("TZ", "UTC")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(3306))))
                .withCommand("--default-time-zone=+00:00", "--sql-mode=NO_ENGINE_SUBSTITUTION",
                        "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
                .withStartupTimeout(Duration.ofMinutes(3))) {
            mysql.start();
            var bindings = mysql.getContainerInfo().getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(3306));
            CompatibilitySupport.require(bindings != null && bindings.length > 0
                            && java.util.Arrays.stream(bindings).allMatch(binding -> "127.0.0.1".equals(binding.getHostIp())),
                    "Fixture MySQL port is not published exclusively on loopback");
            report.put("portBindingsLoopbackOnly", true);
            String jdbc = "jdbc:mysql://127.0.0.1:" + mysql.getMappedPort(3306) + "/"
                    + CompatibilitySupport.DATABASE
                    + "?serverTimezone=Asia/Seoul&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
            try (Connection connection = connect(jdbc)) {
                requireEmpty(connection);
                loadSchema(connection);
                report.put("mysql", serverSettings(connection));
                Map<String, String> baseline = CompatibilitySupport.schemaDescription(connection);
                CompatibilitySupport.require(baseline.size() == FixtureContract.ENTITY_COUNT,
                        "Schema fixture must contain one table per demo entity");
                String initialSchema = CompatibilitySupport.schemaFingerprint(connection);
                report.put("schemaFingerprintBefore", initialSchema);
                runPhase(connection, jdbc, baseline, "legacy", "validate", "legacy", true);
                runPhase(connection, jdbc, baseline, "candidate", "validate", "legacy", true);
                runPhase(connection, jdbc, baseline, "legacy", "write", "legacy", false);
                runPhase(connection, jdbc, baseline, "candidate", "read", "legacy", true);
                runPhase(connection, jdbc, baseline, "candidate", "write", "candidate", false);
                runPhase(connection, jdbc, baseline, "legacy", "read", "candidate", true);
                runPhase(connection, jdbc, baseline, "candidate", "concurrent-rotate", "legacy", false);
                runPhase(connection, jdbc, baseline, "legacy", "read", "legacy", true);
                runPhase(connection, jdbc, baseline, "legacy", "recover", "legacy", true);
                runPhase(connection, jdbc, baseline, "legacy", "concurrent-rotate", "legacy", false);
                runPhase(connection, jdbc, baseline, "candidate", "read", "legacy", true);
                runPhase(connection, jdbc, baseline, "candidate", "recover", "legacy", true);
                runPhase(connection, jdbc, baseline, "legacy", "read", "legacy", true);
                runPhase(connection, jdbc, baseline, "candidate", "read", "candidate", true);
                report.put("coreSchemaFingerprintAfter", CompatibilitySupport.schemaFingerprint(connection));
                CompatibilitySupport.require(initialSchema.equals(report.get("coreSchemaFingerprintAfter")),
                        "Core compatibility checks changed the original schema");
                // Inspect update mode separately after the compatibility checks in validate mode.
                // Both modes are exercised only against the disposable fixture.
                runPhase(connection, jdbc, baseline, "legacy", "update", "legacy", true);
                runPhase(connection, jdbc, baseline, "candidate", "update", "candidate", true);
                report.put("schemaFingerprintAfter", CompatibilitySupport.schemaFingerprint(connection));
                report.put("coreSchemaUnchanged", initialSchema.equals(report.get("coreSchemaFingerprintAfter")));
                report.put("updateDiagnosticSchemaUnchanged", initialSchema.equals(report.get("schemaFingerprintAfter")));
                report.put("status", "passed");
                report.put("deploymentGate", "compatible-only-with-ddl-auto-validate");
            }
        } catch (Exception failure) {
            report.put("status", "failed"); report.put("deploymentGate", "NO_GO");
            report.put("failureClass", failure.getClass().getName());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            CompatibilitySupport.writeJson(reports.resolve("compatibility.json"), report);
        }
    }

    private void runPhase(Connection connection, String jdbc, Map<String, String> baseline,
                          String runtime, String mode, String label, boolean requireSameData) throws Exception {
        String phaseName = "%02d-%s-%s-%s".formatted(phases.size() + 1, runtime, mode, label);
        Path publicReport = reports.resolve("phases").resolve(phaseName + ".json");
        Path privateLog = privateFiles.resolve(phaseName + ".log");
        Path state = privateFiles.resolve(label + "-state.json");
        String beforeData = CompatibilitySupport.dataFingerprint(connection);
        List<String> command = childCommand(runtime, mode, state, publicReport, label, false);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(privateLog.toFile());
        builder.environment().put("DB_COMPAT_JDBC_URL", jdbc);
        builder.environment().put("DB_COMPAT_EXPECT_HIBERNATE", runtime.equals("legacy") ? "6.5.3.Final" : "7.4.13.Final");
        Process process = builder.start();
        boolean ended = process.waitFor(75, TimeUnit.SECONDS);
        if (!ended) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", phaseName); result.put("runtime", runtime); result.put("mode", mode);
        result.put("processSucceeded", ended && process.exitValue() == 0);
        phases.add(result);
        if (!ended || process.exitValue() != 0) {
            printSafeFailure(privateLog);
            throw new IllegalStateException("Compatibility phase failed: " + phaseName);
        }
        var childResult = CompatibilitySupport.JSON.readTree(publicReport.toFile());
        result.put("ddlStatementCount", childResult.path("ddlStatementCount").asInt(-1));
        CompatibilitySupport.require(childResult.has("ddlStatementCount"), "Missing DDL execution evidence");
        Map<String, String> after = CompatibilitySupport.schemaDescription(connection);
        List<String> changed = CompatibilitySupport.changedTables(baseline, after);
        result.put("schemaChangedTables", changed);
        result.put("schemaUnchanged", changed.isEmpty());
        boolean diagnostic = mode.equals("update");
        result.put("diagnosticOnly", diagnostic);
        CompatibilitySupport.require(diagnostic || changed.isEmpty(), "Schema mutation detected in isolated compatibility phase");
        String afterData = CompatibilitySupport.dataFingerprint(connection);
        result.put("dataFingerprintUnchanged", beforeData.equals(afterData));
        result.put("dataInvariantRequired", requireSameData && !diagnostic);
        result.put("schemaInvariantRequired", !diagnostic);
        CompatibilitySupport.require(diagnostic || !requireSameData || beforeData.equals(afterData),
                "Validation/read/recovery unexpectedly changed synthetic rows");
        System.out.println((diagnostic ? "Recorded diagnostic " : "Passed ") + phaseName
                + " (changed tables=" + changed.size() + ")");
    }

    static List<String> childCommand(String runtime, String mode, Path state, Path report, String label, boolean h2) {
        String classpath = System.getProperty("compat." + runtime + ".classpath");
        CompatibilitySupport.require(classpath != null && !classpath.isBlank(), "Missing isolated ORM classpath");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows")
                ? "java.exe" : "java").toString());
        String timezone = System.getenv().getOrDefault("DB_COMPAT_JVM_TIMEZONE", "UTC");
        CompatibilitySupport.require(List.of("UTC", "Asia/Seoul").contains(timezone), "Unsupported compatibility JVM timezone");
        command.add("-Duser.timezone=" + timezone); command.add("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn");
        if (h2) command.add("-Dcompat.h2.smoke=true");
        command.add("-cp"); command.add(classpath); command.add(CompatibilityPhase.class.getName());
        command.add(mode); command.add(state.toString()); command.add(report.toString()); command.add(label);
        return command;
    }

    private static Connection connect(String jdbc) throws Exception {
        return DriverManager.getConnection(jdbc, CompatibilitySupport.USER, CompatibilitySupport.PASSWORD);
    }

    private static void requireEmpty(Connection connection) throws Exception {
        CompatibilitySupport.require(connection.getCatalog().equals(CompatibilitySupport.DATABASE),
                "Unexpected schema name");
        CompatibilitySupport.require(CompatibilitySupport.tableNames(connection).isEmpty(),
                "Refusing to initialize a nonempty database");
    }

    private void loadSchema(Connection connection) throws Exception {
        String sql = Files.readString(schemaFile, StandardCharsets.UTF_8);
        CompatibilitySupport.require(!Pattern.compile("(?i)\\b(?:INSERT|REPLACE|DROP|USE|DEFINER|DATABASE)\\b")
                        .matcher(sql.replaceAll("'(?:''|[^'])*'", "''")).find(),
                "Schema fixture must not contain data, host names, database selection or destructive statements");
        String[] statements = sql.split(";\\s*(?:\\R|$)");
        int count = 0;
        // mysqldump orders child tables before some referenced parents. Disable
        // checks only while restoring an empty, disposable schema, then restore.
        try (Statement control = connection.createStatement()) { control.execute("SET FOREIGN_KEY_CHECKS=0"); }
        try {
            for (String statement : statements) {
                if (statement.isBlank()) continue;
                CompatibilitySupport.require(statement.stripLeading().startsWith("CREATE TABLE `"),
                        "Only reviewed CREATE TABLE metadata is accepted");
                try (Statement jdbcStatement = connection.createStatement()) { jdbcStatement.execute(statement); }
                count++;
            }
        } finally {
            try (Statement control = connection.createStatement()) { control.execute("SET FOREIGN_KEY_CHECKS=1"); }
        }
        CompatibilitySupport.require(count == FixtureContract.ENTITY_COUNT,
                "Schema fixture must contain one table per demo entity");
    }

    private Map<String, Object> serverSettings(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT VERSION(),@@global.time_zone,@@session.time_zone,@@system_time_zone,@@sql_mode,"
                        + "@@lower_case_table_names,@@character_set_server,@@collation_server,@@global.sql_mode")) {
            CompatibilitySupport.require(rows.next(), "MySQL settings unavailable");
            Map<String, Object> values = new LinkedHashMap<>();
            String[] keys = {"version", "globalTimeZone", "sessionTimeZone", "systemTimeZone", "jdbcSessionSqlMode",
                    "lowerCaseTableNames", "characterSet", "collation", "globalSqlMode"};
            for (int i = 0; i < keys.length; i++) values.put(keys[i], rows.getString(i + 1));
            report.put("mysql", values); // Retain non-sensitive settings even if a precondition fails.
            CompatibilitySupport.require(rows.getString(1).startsWith("8.4.9"), "Fixture requires MySQL 8.4.9");
            CompatibilitySupport.require(List.of("UTC", "+00:00").contains(rows.getString(2))
                            && List.of("UTC", "+00:00").contains(rows.getString(3))
                            && List.of("UTC", "Etc/UTC").contains(rows.getString(4)),
                    "Fixture MySQL global/session/system time zones must use UTC");
            CompatibilitySupport.require(CompatibilitySupport.sqlModes(rows.getString(9))
                    .equals(java.util.Set.of("NO_ENGINE_SUBSTITUTION")), "Fixture server global SQL mode is unexpected");
            CompatibilitySupport.require(CompatibilitySupport.sqlModes(rows.getString(5))
                    .equals(java.util.Set.of("STRICT_TRANS_TABLES", "NO_ENGINE_SUBSTITUTION")),
                    "JDBC session SQL mode differs from legacy/candidate driver behavior");
            CompatibilitySupport.require(rows.getInt(6) == 0 && rows.getString(7).equals("utf8mb4")
                    && rows.getString(8).equals("utf8mb4_0900_ai_ci"), "Fixture table name case, character set or collation is unexpected");
            return values;
        }
    }

    private static void printSafeFailure(Path log) throws Exception {
        // The database and credentials are disposable fixtures. Still redact token-like strings
        // and JDBC URLs before printing a bounded diagnostic; never upload the private directory.
        Files.readAllLines(log).stream().filter(line -> line.contains("Exception") || line.contains("Schema-validation")
                        || line.contains("ERROR") || line.contains("Caused by"))
                .limit(18).map(line -> line.replaceAll("jdbc:[^\\s]+", "[fixture-jdbc]")
                        .replaceAll("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]+", "[synthetic-token]"))
                .forEach(System.err::println);
    }
}
