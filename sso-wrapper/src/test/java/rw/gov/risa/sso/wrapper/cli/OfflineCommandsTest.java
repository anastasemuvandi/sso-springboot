package rw.gov.risa.sso.wrapper.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;

/** The offline ops commands (no Keycloak): fgap-plan(-check), delegated-validate, registry-validate. */
class OfflineCommandsTest {

    static final DelegationProperties PROPS = new DelegationProperties("configs/delegated-permissions/templates.json",
            "configs/institutions.yaml", true, true, "institution", true);

    @TempDir
    Path tmp;

    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    PrintStream out() {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    String printed() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    static Path repo(String relative) {
        Path here = Path.of("").toAbsolutePath();
        for (Path base : List.of(here, here.getParent())) {
            if (Files.exists(base.resolve(relative))) {
                return base.resolve(relative);
            }
        }
        throw new IllegalStateException(relative + " not found");
    }

    // ---- fgap-plan-check -------------------------------------------------------------------

    @Test
    void fgapPlanCheckPassesAgainstTheCommittedFile() {
        int code = new FgapPlanCommands.Check(PROPS).printTo(out()).run(List.of());
        assertThat(code).as(printed()).isZero();
        assertThat(printed()).contains("is up to date");
    }

    @Test
    void fgapPlanCheckFailsWhenTheFileIsAltered() throws Exception {
        Path copy = tmp.resolve("fgap-v2-plan.json");
        String committed = Files.readString(repo("configs/generated/fgap-v2-plan.json"));
        Files.writeString(copy, committed.replace("\"inst:minagri:institution-admin\"", "\"inst:minagri:tampered\""));
        int code = new FgapPlanCommands.Check(PROPS).printTo(out()).run(List.of("--plan", copy.toString()));
        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("STALE");
    }

    @Test
    void fgapPlanCheckFailsWhenTheFileIsMissing() {
        int code = new FgapPlanCommands.Check(PROPS).printTo(out())
                .run(List.of("--plan", tmp.resolve("absent.json").toString()));
        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("MISSING");
    }

    @Test
    void fgapPlanRendersTheCommittedFileByteForByteWithCrlf() throws Exception {
        Path target = tmp.resolve("out/plan.json");
        int code = new FgapPlanCommands.Render(PROPS).printTo(out()).run(List.of("--output", target.toString()));
        assertThat(code).as(printed()).isZero();
        assertThat(Files.readAllBytes(target)).isEqualTo(Files.readAllBytes(repo("configs/generated/fgap-v2-plan.json")));
        assertThat(printed()).contains("institution(s)").contains("permission(s)");
    }

    @Test
    void unknownOptionIsAUsageError() {
        assertThat(new FgapPlanCommands.Check(PROPS).printTo(out()).run(List.of("--bogus")))
                .isEqualTo(PrintingCommand.USAGE);
    }

    // ---- delegated-validate ----------------------------------------------------------------

    @Test
    void delegatedValidateAcceptsTheCommittedTemplates() {
        int code = new DelegatedValidateCommand(PROPS).printTo(out()).run(List.of());
        assertThat(code).as(printed()).isZero();
        assertThat(printed()).contains("Config is valid").contains("institution-admin");
    }

    @Test
    void delegatedValidateRejectsAnUnsafeTemplate() throws Exception {
        String committed = Files.readString(repo("configs/delegated-permissions/templates.json"));
        // An allow-pattern that reaches a broad role: the guardrail must reject the whole file.
        String unsafe = committed.replaceFirst("\"allow\": \\[\"inst:\\{slug\\}:\\*\"\\]",
                "\"allow\": [\"inst:{slug}:*\", \"super_admin\"]");
        assertThat(unsafe).isNotEqualTo(committed);
        Path file = tmp.resolve("templates.json");
        Files.writeString(file, unsafe);
        int code = new DelegatedValidateCommand(PROPS).printTo(out()).run(List.of("--config", file.toString()));
        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("INVALID");
    }

    @Test
    void delegatedValidateFailsForAMissingFile() {
        int code = new DelegatedValidateCommand(PROPS).printTo(out())
                .run(List.of("--config", tmp.resolve("nope.json").toString()));
        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("not found");
    }

    // ---- registry-validate -----------------------------------------------------------------

    @Test
    void registryValidateIsCleanForTheCommittedRegistry() {
        int code = new RegistryValidateCommand(PROPS).printTo(out()).run(List.of());
        assertThat(code).as(printed()).isZero();
        assertThat(printed()).contains("Result: CLEAN");
    }

    @Test
    void registryValidateReportsADuplicateSlug() throws Exception {
        Path file = tmp.resolve("institutions.yaml");
        Files.writeString(file, """
                institutions:
                  - slug: minagri
                    name: Ministry of Agriculture
                    ippis_codes: ["A1"]
                  - slug: minagri
                    name: Again
                    ippis_codes: ["A2"]
                """);
        int code = new RegistryValidateCommand(PROPS).printTo(out()).run(List.of("--registry", file.toString()));
        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("FINDINGS PRESENT");
    }

    // ---- args ------------------------------------------------------------------------------

    @Test
    void cliArgsParsesFlagsRepeatablesAndEqualsForm() {
        CliArgs a = CliArgs.parse(List.of("--dry-run", "--institution", "minagri", "--institution=minisante", "x"),
                java.util.Set.of("dry-run"), java.util.Set.of("institution"));
        assertThat(a.has("dry-run")).isTrue();
        assertThat(a.all("institution")).containsExactly("minagri", "minisante");
        assertThat(a.unknown()).containsExactly("x");
    }
}
