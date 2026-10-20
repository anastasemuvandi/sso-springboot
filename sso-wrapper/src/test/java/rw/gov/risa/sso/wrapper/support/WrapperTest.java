package rw.gov.risa.sso.wrapper.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context, MockMvc, profile "test", no live Keycloak/Redis required.
 * Mock what you call with {@code @MockitoBean} (e.g. KeycloakAdminFactory, AuditEmitter).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "plainlog"})
public @interface WrapperTest {}
