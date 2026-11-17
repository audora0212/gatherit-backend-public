package compat;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One ORM version per JVM: no mixed Hibernate/JDBC classes and no real application startup. */
public final class CompatibilityPhase {
    private static final String ATTEMPT = "a".repeat(64);
    public record State(Map<String, String> fixture, JsonNode expected) {}

    private CompatibilityPhase() {}

    public static void main(String[] args) throws Exception {
        CompatibilitySupport.require(args.length == 4, "Expected mode, state path, report path and label");
        String mode = args[0], label = args[3];
        Path stateFile = Path.of(args[1]), reportFile = Path.of(args[2]);
        String jdbc = System.getenv("DB_COMPAT_JDBC_URL");
        validateTarget(jdbc, mode);
        String hibernate = org.hibernate.Version.getVersionString();
        String expectedVersion = System.getenv("DB_COMPAT_EXPECT_HIBERNATE");
        CompatibilitySupport.require(hibernate.equals(expectedVersion), "Unexpected ORM runtime version");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", mode); report.put("label", label);
        report.put("hibernate", hibernate); report.put("java", System.getProperty("java.version"));
        report.put("jvmTimezone", java.util.TimeZone.getDefault().getID());
        report.put("startedAt", Instant.now().toString());
        try (var probe = java.sql.DriverManager.getConnection(jdbc,
                CompatibilitySupport.USER, CompatibilitySupport.PASSWORD)) {
            report.put("jdbcDriver", probe.getMetaData().getDriverName());
            report.put("jdbcDriverVersion", probe.getMetaData().getDriverVersion());
            report.put("databaseVersion", probe.getMetaData().getDatabaseProductVersion());
            if (jdbc.startsWith("jdbc:mysql:")) {
                report.put("mysqlSession", CompatibilitySupport.jdbcSessionSettings(probe));
                String required = expectedVersion.startsWith("6.") ? "8.3." : "9.7.";
                CompatibilitySupport.require(probe.getMetaData().getDriverVersion().contains(required),
                        "Unexpected legacy/candidate JDBC driver version");
            }
        }
        StandardServiceRegistry registry = null;
        try {
            Map<String, Object> settings = new HashMap<>();
            settings.put("hibernate.connection.url", jdbc);
            settings.put("hibernate.connection.username", CompatibilitySupport.USER);
            settings.put("hibernate.connection.password", CompatibilitySupport.PASSWORD);
            settings.put("hibernate.connection.driver_class", jdbc.startsWith("jdbc:mysql:")
                    ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
            settings.put("hibernate.dialect", jdbc.startsWith("jdbc:mysql:")
                    ? "org.hibernate.dialect.MySQLDialect" : "org.hibernate.dialect.H2Dialect");
            settings.put("hibernate.hbm2ddl.auto", mode.equals("create") ? "create"
                    : mode.equals("update") ? "update" : "validate");
            settings.put("hibernate.hbm2ddl.halt_on_error", true);
            settings.put("hibernate.jdbc.time_zone", "Asia/Seoul");
            settings.put("hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            settings.put("hibernate.implicit_naming_strategy",
                    "org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl");
            settings.put("hibernate.connection.pool_size", 4);
            settings.put("hibernate.connection.provider_class", CapturingConnectionProvider.class.getName());
            settings.put("hibernate.show_sql", false);
            settings.put("jakarta.persistence.validation.mode", "none");
            CapturingConnectionProvider.clearCaptured();
            registry = new StandardServiceRegistryBuilder().applySettings(settings).build();
            var sources = new MetadataSources(registry);
            var entities = FixtureContract.entityTypes();
            CompatibilitySupport.require(entities.size() == FixtureContract.ENTITY_COUNT,
                    "Demo entity mapping is incomplete");
            entities.forEach(sources::addAnnotatedClass);
            try (SessionFactory factory = sources.buildMetadata().buildSessionFactory()) {
                report.put("mappedEntities", ((EntityManagerFactory) factory).getMetamodel().getEntities().size());
                switch (mode) {
                    case "create", "validate", "update" -> { /* ORM startup performs the selected schema operation. */ }
                    case "write" -> {
                        State state = inTransaction(factory, em -> {
                            var fixture = FixtureContract.seed(em, label);
                            em.flush(); em.clear();
                            FixtureContract.verifyPasswords(em, fixture);
                            return new State(fixture, CompatibilitySupport.JSON.valueToTree(
                                    FixtureContract.snapshot(em, fixture)));
                        });
                        CompatibilitySupport.writeJson(stateFile, state);
                        report.put("fixtureCreated", true);
                    }
                    case "read" -> {
                        State state = readState(stateFile);
                        inTransaction(factory, em -> {
                            FixtureContract.verifyPasswords(em, state.fixture());
                            assertSnapshot(em, state);
                            return null;
                        });
                        report.put("snapshotMatched", true);
                        report.put("passwordCompatibility", true);
                    }
                    case "concurrent-rotate" -> {
                        State state = readState(stateFile);
                        String presented = state.fixture().get("activeToken");
                        CompatibilitySupport.require(presented != null, "Synthetic active session is missing");
                        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
                        var pool = Executors.newFixedThreadPool(2);
                        List<Map<String, String>> rotations = new ArrayList<>();
                        try {
                            Callable<Map<String, String>> operation = () -> inTransaction(factory, em -> {
                                ready.countDown();
                                CompatibilitySupport.require(start.await(10, TimeUnit.SECONDS), "Refresh start timed out");
                                return FixtureContract.rotate(em, new HashMap<>(state.fixture()), presented, ATTEMPT);
                            });
                            var first = pool.submit(operation); var second = pool.submit(operation);
                            CompatibilitySupport.require(ready.await(10, TimeUnit.SECONDS), "Concurrent workers not ready");
                            start.countDown();
                            rotations.add(first.get(30, TimeUnit.SECONDS));
                            rotations.add(second.get(30, TimeUnit.SECONDS));
                        } finally {
                            pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS);
                        }
                        String firstToken = rotations.get(0).get("activeToken");
                        CompatibilitySupport.require(firstToken != null
                                && firstToken.equals(rotations.get(1).get("activeToken")),
                                "Concurrent refresh returned different active sessions");
                        CompatibilitySupport.require(rotations.stream()
                                .filter(result -> "rotated".equals(result.get("outcome"))).count() == 1,
                                "Concurrent refresh must rotate once");
                        var fixture = new HashMap<>(state.fixture()); fixture.putAll(rotations.get(0));
                        fixture.put("activeToken", firstToken);
                        State next = inTransaction(factory, em -> new State(fixture,
                                CompatibilitySupport.JSON.valueToTree(FixtureContract.snapshot(em, fixture))));
                        CompatibilitySupport.writeJson(stateFile, next);
                        report.put("concurrentResults", rotations.stream().map(result -> result.get("outcome")).sorted().toList());
                        report.put("oneActiveSession", true);
                    }
                    case "recover" -> {
                        State state = readState(stateFile);
                        var fixture = new HashMap<>(state.fixture()); fixture.put("clockOffsetSeconds", "61");
                        String previous = fixture.get("previousToken");
                        CompatibilitySupport.require(previous != null, "Previous synthetic token is missing");
                        Map<String, String> result = inTransaction(factory, em -> {
                            var rotation = FixtureContract.rotate(em, fixture, previous, ATTEMPT);
                            CompatibilitySupport.require("attempt_recovered".equals(rotation.get("outcome")),
                                    "Recovery after grace did not reuse the existing session");
                            CompatibilitySupport.require(state.fixture().get("activeToken").equals(rotation.get("activeToken")),
                                    "Recovery changed the active session");
                            assertSnapshot(em, state);
                            return rotation;
                        });
                        report.put("recoveryOutcome", result.get("outcome"));
                        report.put("sessionAndExpiryUnchanged", true);
                    }
                    default -> throw new IllegalArgumentException("Unknown compatibility phase");
                }
            }
            if (!mode.equals("create") && !mode.equals("update")) {
                CompatibilitySupport.require(CapturingConnectionProvider.capturedDdl().isEmpty(),
                        "Validation/read/session phase attempted DDL");
            }
            report.put("status", "passed");
        } catch (Exception failure) {
            report.put("status", "failed");
            report.put("failureClass", failure.getClass().getName());
            // No exception message or JDBC credentials are copied into the public report.
            throw failure;
        } finally {
            if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
            report.put("ddlStatementCount", CapturingConnectionProvider.capturedDdl().size());
            report.put("ddlStatementSha256", CapturingConnectionProvider.capturedDdl().stream()
                    .map(CompatibilitySupport::sha256).toList());
            report.put("finishedAt", Instant.now().toString());
            CompatibilitySupport.writeJson(reportFile, report);
        }
    }

    private static State readState(Path file) throws Exception {
        CompatibilitySupport.require(Files.isRegularFile(file), "Synthetic state file is missing");
        return CompatibilitySupport.JSON.readValue(file.toFile(), State.class);
    }

    private static void assertSnapshot(EntityManager em, State state) throws Exception {
        // Compare the JSON wire value, not Jackson's in-memory IntNode/LongNode choice.
        // Persisted state is parsed from JSON, so normalize the actual value the same way.
        JsonNode actual = CompatibilitySupport.JSON.readTree(CompatibilitySupport.JSON.writeValueAsBytes(
                FixtureContract.snapshot(em, state.fixture())));
        CompatibilitySupport.require(state.expected().equals(actual),
                "Cross-version DTO, data, timestamp or session snapshot changed: "
                        + changedPaths(state.expected(), actual, ""));
    }

    private static List<String> changedPaths(JsonNode expected, JsonNode actual, String path) {
        List<String> differences = new ArrayList<>();
        if (expected.equals(actual)) return differences;
        if (expected.isObject() && actual.isObject()) {
            var names = new java.util.TreeSet<String>();
            expected.fieldNames().forEachRemaining(names::add); actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) differences.addAll(changedPaths(expected.path(name), actual.path(name), path + "/" + name));
        } else if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) differences.addAll(changedPaths(expected.get(i), actual.get(i), path + "/" + i));
        } else differences.add(path + " [" + expected.getClass().getSimpleName() + "/" + actual.getClass().getSimpleName() + "]");
        return differences.stream().limit(20).toList();
    }

    @FunctionalInterface interface Work<T> { T apply(EntityManager em) throws Exception; }
    private static <T> T inTransaction(EntityManagerFactory factory, Work<T> work) throws Exception {
        try (EntityManager em = factory.createEntityManager()) {
            var transaction = em.getTransaction(); transaction.begin();
            try {
                T result = work.apply(em); transaction.commit(); return result;
            } catch (Exception failure) {
                if (transaction.isActive()) transaction.rollback();
                throw failure;
            }
        }
    }

    private static void validateTarget(String jdbc, String mode) {
        CompatibilitySupport.require(jdbc != null, "Fixture JDBC URL is required");
        if (jdbc.startsWith("jdbc:h2:file:") && Boolean.getBoolean("compat.h2.smoke")) return;
        CompatibilitySupport.require(jdbc.startsWith("jdbc:mysql:"), "Only the isolated MySQL fixture is allowed");
        CompatibilitySupport.require("true".equals(System.getenv("GITHUB_ACTIONS"))
                        && "true".equals(System.getenv("GATHERIT_RUN_DB_COMPAT")),
                "Direct MySQL phases require explicitly enabled GitHub CI; local Docker/database use is forbidden");
        CompatibilitySupport.require(!mode.equals("create"), "MySQL create mode is forbidden; use reviewed schema metadata");
        URI uri = URI.create(jdbc.substring("jdbc:".length()));
        CompatibilitySupport.require(List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost())
                && uri.getPort() > 0 && uri.getPath().equals("/" + CompatibilitySupport.DATABASE),
                "Remote or non-fixture databases are forbidden");
    }
}
