package com.example.learnhub.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-026 and constitution Principle IX: the parent POM is the only place a version is declared.
 *
 * <p>Expected to pass from the start; it exists to keep passing. A version slipping into a child POM
 * is the kind of change that looks harmless in review and then produces two modules on different
 * library versions.
 */
class BuildRulesTest {

    private static final Path BACKEND = Path.of("..");
    private static final Path PARENT_POM = BACKEND.resolve("pom.xml");

    /** A <version> element that is not the one inside the <parent> block. */
    private static final Pattern VERSION_ELEMENT = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);

    private static List<Path> childPoms;

    @BeforeAll
    static void findPoms() throws IOException {
        Assumptions.assumeTrue(Files.exists(PARENT_POM), "backend/pom.xml not present");
        try (Stream<Path> entries = Files.list(BACKEND)) {
            childPoms = entries
                    .filter(Files::isDirectory)
                    .map(path -> path.resolve("pom.xml"))
                    .filter(Files::exists)
                    .toList();
        }
    }

    @Test
    @DisplayName("no child POM declares a version outside its <parent> block")
    void childPomsDeclareNoVersions() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path pom : childPoms) {
            String withoutParent = PARENT_BLOCK.matcher(Files.readString(pom)).replaceAll("");
            Matcher versions = VERSION_ELEMENT.matcher(withoutParent);
            while (versions.find()) {
                violations.add("%s declares <version>%s</version> outside <parent>"
                        .formatted(BACKEND.relativize(pom), versions.group(1)));
            }
        }

        assertThat(violations).as("versions belong in backend/pom.xml alone").isEmpty();
    }

    @Test
    @DisplayName("every module on disk is registered in the parent POM")
    void everyModuleIsRegistered() throws IOException {
        String parent = Files.readString(PARENT_POM);

        List<String> unregistered = new ArrayList<>();
        for (Path pom : childPoms) {
            String module = pom.getParent().getFileName().toString();
            if (!parent.contains("<module>" + module + "</module>")) {
                unregistered.add(module);
            }
        }

        assertThat(unregistered).as("modules present on disk but absent from <modules>").isEmpty();
    }

    @Test
    @DisplayName("every registered module exists on disk")
    void everyRegisteredModuleExists() throws IOException {
        String parent = Files.readString(PARENT_POM);
        Matcher modules = Pattern.compile("<module>([^<]+)</module>").matcher(parent);

        List<String> missing = new ArrayList<>();
        while (modules.find()) {
            String module = modules.group(1);
            if (!Files.exists(BACKEND.resolve(module).resolve("pom.xml"))) {
                missing.add(module);
            }
        }

        assertThat(missing).as("modules listed in <modules> but not on disk").isEmpty();
    }

    @Test
    @DisplayName("the parent POM pins exact versions, never a range or LATEST")
    void parentPinsExactVersions() throws IOException {
        String parent = Files.readString(PARENT_POM);

        assertThat(parent).doesNotContain("LATEST").doesNotContain("RELEASE");
        assertThat(parent).as("no Maven version ranges").doesNotContainPattern("<version>[\\[(]");
    }

    @Test
    @DisplayName("services depend on platform-common without naming a version")
    void servicesUsePlatformCommonWithoutVersion() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path pom : childPoms) {
            String content = Files.readString(pom);
            if (!content.contains("<artifactId>platform-common</artifactId>")) {
                continue;
            }
            int index = content.indexOf("<artifactId>platform-common</artifactId>");
            String nearby = content.substring(index, Math.min(content.length(), index + 200));
            if (nearby.contains("<version>")) {
                violations.add(BACKEND.relativize(pom).toString());
            }
        }

        assertThat(violations).as("the parent manages this version").isEmpty();
    }

    @Test
    @DisplayName("the scan actually found the modules")
    void scanIsNotVacuous() {
        assertThat(childPoms).hasSizeGreaterThanOrEqualTo(6);
    }
}
