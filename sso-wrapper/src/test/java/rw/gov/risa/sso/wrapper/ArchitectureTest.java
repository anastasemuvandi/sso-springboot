package rw.gov.risa.sso.wrapper;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Layering rules for the wrapper (SPEC §2). Each rule is its own clearly named
 * {@link ArchTest} field; add further layering rules as new fields.
 */
@AnalyzeClasses(packages = "rw.gov.risa.sso.wrapper", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Policy modules are pure Java: no Spring, no servlet API, no Keycloak client
     * types and no HTTP client. Services adapt Keycloak representations into the
     * plain records the policy modules take.
     */
    @ArchTest
    static final ArchRule policyModulesArePureJava = noClasses()
            .that().resideInAPackage("..wrapper.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.servlet..",
                    "org.keycloak..",
                    "java.net.http..")
            .because("policy modules are pure Java with no Spring and no HTTP (SPEC §2)");

    /**
     * Policy records carry no wire-format annotations; loaders parse config files
     * with a plain ObjectMapper tree and map them into records by hand.
     */
    @ArchTest
    static final ArchRule policyModulesHaveNoJacksonAnnotations = noClasses()
            .that().resideInAPackage("..wrapper.policy..")
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson.annotation..")
            .because("DTO annotations belong to the api layer, not to policy records");

    /** Policy modules sit below the api and service layers and never call up into them. */
    @ArchTest
    static final ArchRule policyModulesDoNotDependOnApiOrService = noClasses()
            .that().resideInAPackage("..wrapper.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..wrapper.api..",
                    "..wrapper.service..")
            .because("policy modules are the bottom layer: api -> service -> policy");
}
