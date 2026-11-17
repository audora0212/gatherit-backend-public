package compat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fast fixture/runtime smoke. A passing result explicitly does not authorize deployment. */
public final class H2Smoke {
    private H2Smoke() {}
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "local-no-docker-");
        String jdbc = "jdbc:h2:file:" + run.resolve("fixture").toString().replace('\\', '/')
                + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
        Path state = run.resolve("synthetic-state.json");
        String[][] cases = {{"legacy", "create"}, {"legacy", "write"}, {"candidate", "read"},
                {"candidate", "concurrent-rotate"}, {"legacy", "read"}, {"legacy", "recover"},
                {"legacy", "concurrent-rotate"}, {"candidate", "read"}, {"candidate", "recover"}};
        for (int i = 0; i < cases.length; i++) {
            String runtime = cases[i][0], mode = cases[i][1];
            Path report = run.resolve("%02d-%s-%s.json".formatted(i + 1, runtime, mode));
            List<String> command = CompatibilityCoordinator.childCommand(runtime, mode, state, report, "smoke", true);
            var builder = new ProcessBuilder(command).inheritIO();
            builder.environment().put("DB_COMPAT_JDBC_URL", jdbc);
            builder.environment().put("DB_COMPAT_EXPECT_HIBERNATE", runtime.equals("legacy") ? "6.5.3.Final" : "7.4.13.Final");
            Process process = builder.start();
            boolean ended = process.waitFor(60, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            CompatibilitySupport.require(ended && process.exitValue() == 0, "H2 fixture smoke failed: " + mode);
        }
        System.out.println("H2_SMOKE_ONLY_PASSED: Docker not used; MySQL deployment gate remains unverified.");
    }
}
