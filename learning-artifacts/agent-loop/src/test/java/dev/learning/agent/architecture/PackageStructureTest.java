package dev.learning.agent.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the dependency boundaries that motivated the package reorganization. */
class PackageStructureTest {
    private static final String PREFIX = "dev.learning.agent.";
    private static final Path MAIN = Path.of("src/main/java/dev/learning/agent");

    @Test
    void algorithmPackagesOnlyShareTheIntendedReactEngine() throws IOException {
        assertNoImportsFrom("core/react", "core.rewoo", "core.planexecute");
        assertNoImportsFrom("core/rewoo", "core.react", "core.planexecute");
        assertNoImportsFrom("core/planexecute", "core.rewoo");
        assertNoImportsFrom("core/planning", "core.react", "core.rewoo", "core.planexecute");
    }

    @Test
    void sharedModelsDoNotDependOnProtocolAdaptersOrStrategies() throws IOException {
        assertNoImportsFrom("model", "deepseek", "app", "core.react", "core.rewoo", "core.planexecute");
    }

    @Test
    void jsonSupportDoesNotKnowBusinessTypes() throws IOException {
        assertNoImportsFrom("support/json", "app", "core", "model", "tool", "deepseek");
    }

    @Test
    void sourceAndTestDirectoriesMatchTheirPackageDeclarations() throws IOException {
        for (Path root : List.of(MAIN, Path.of("src/test/java/dev/learning/agent"))) {
            for (Path source : javaFiles(root)) {
                String expected = PREFIX + root.relativize(source.getParent()).toString().replace('/', '.');
                if (expected.endsWith(".")) expected = expected.substring(0, expected.length() - 1);
                assertTrue(Files.readString(source).startsWith("package " + expected + ";"), source.toString());
            }
        }
    }

    private void assertNoImportsFrom(String directory, String... forbiddenPackages) throws IOException {
        for (Path source : javaFiles(MAIN.resolve(directory))) {
            String content = Files.readString(source);
            for (String forbidden : forbiddenPackages) {
                Pattern imported = Pattern.compile("(?m)^import (?:static )?"
                        + Pattern.quote(PREFIX + forbidden) + "\\.");
                assertFalse(imported.matcher(content).find(), source + " depends on " + forbidden);
            }
        }
    }

    private List<Path> javaFiles(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
