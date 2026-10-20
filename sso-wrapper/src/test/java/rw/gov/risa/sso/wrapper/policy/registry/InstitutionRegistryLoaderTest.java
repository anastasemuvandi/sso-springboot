package rw.gov.risa.sso.wrapper.policy.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstitutionRegistryLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void theShippedRegistryLoadsAndMapsEverySeededInstitution() throws IOException {
        Path path = RepoFiles.find("configs/institutions.yaml");
        InstitutionRegistry reg = InstitutionRegistryLoader.load(path);
        assertThat(reg.slugs()).isNotEmpty().contains("minagri", "minisante", "mineduc");
        reg.codes().forEach((code, slug) -> assertThat(reg.slugForIppisCode(code)).contains(slug));
        assertThat(reg.slugForIppisCode("MINEDUC")).contains("mineduc");
        assertThat(reg.warnings()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theShippedRegistryPassesTheValidatorAgainstTheShippedTemplates() throws IOException {
        Object doc = InstitutionRegistryLoader.readRaw(RepoFiles.find("configs/institutions.yaml"));
        Map<String, Object> templates = new ObjectMapper().readValue(
                RepoFiles.find("configs/delegated-permissions/templates.json").toFile(), Map.class);
        RegistryFindings f = RegistryValidator.validate(doc, templates);
        assertThat(f.errors()).isEmpty();
    }

    @Test
    void loadOrEmptyNeverThrows() throws IOException {
        assertThat(InstitutionRegistryLoader.loadOrEmpty(tmp.resolve("missing.yaml")).source()).isEqualTo("<missing>");
        assertThat(InstitutionRegistryLoader.loadOrEmpty(null).isEmpty()).isTrue();
        Path bad = Files.writeString(tmp.resolve("bad.yaml"), "institutions: [unclosed");
        InstitutionRegistry r = InstitutionRegistryLoader.loadOrEmpty(bad);
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.source()).isEqualTo(bad.toString());
        assertThatThrownBy(() -> InstitutionRegistryLoader.load(bad)).isInstanceOf(IOException.class);
    }

    @Test
    void parsesYamlIntoPlainTree() throws IOException {
        Path f = Files.writeString(tmp.resolve("r.yaml"), """
                institutions:
                  - slug: minagri
                    ippis_codes: [MINAGRI, MOA]
                """);
        InstitutionRegistry r = InstitutionRegistryLoader.load(f);
        assertThat(r.slugForIppisCode("moa")).contains("minagri");
        assertThat(InstitutionRegistryLoader.parseYaml("  ")).isNull();
        Path empty = Files.writeString(tmp.resolve("e.yaml"), "");
        assertThat(InstitutionRegistryLoader.load(empty).isEmpty()).isTrue();
    }

    @Test
    void firstExisting() throws IOException {
        Path f = Files.writeString(tmp.resolve("x.yaml"), "a: 1");
        assertThat(InstitutionRegistryLoader.firstExisting(List.of(tmp.resolve("nope"), f))).contains(f);
        assertThat(InstitutionRegistryLoader.firstExisting(List.of(tmp.resolve("nope")))).isEmpty();
    }
}
