package rw.gov.risa.sso.wrapper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessProperties;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedPermissionsHolder;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyProperties;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.mfa.MfaFlowInstaller;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.service.serviceaccount.ServiceAccountRoleReconciler;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;

/**
 * The shared services wire up on their own (independently of the feature packages), with the delegated
 * templates found from the working directory and the mail relay unconfigured.
 */
class SharedServicesWiringTest {

    @Configuration
    @EnableConfigurationProperties({SsoProperties.class, ClientAccessProperties.class, DelegationProperties.class,
            JoinKeyProperties.class, MailSettings.class, ServiceAccountRoleReconciler.Properties.class})
    @ComponentScan(basePackages = {"rw.gov.risa.sso.wrapper.service.keycloak", "rw.gov.risa.sso.wrapper.service.clientaccess",
            "rw.gov.risa.sso.wrapper.service.institution", "rw.gov.risa.sso.wrapper.service.clientscope",
            "rw.gov.risa.sso.wrapper.service.joinkey", "rw.gov.risa.sso.wrapper.service.mail",
            "rw.gov.risa.sso.wrapper.service.setuplink", "rw.gov.risa.sso.wrapper.service.mfa",
            "rw.gov.risa.sso.wrapper.service.serviceaccount"})
    static class Shared {
    }

    @Test
    void allSharedServicesStart() {
        new ApplicationContextRunner()
                .withUserConfiguration(Shared.class)
                .withBean(KeycloakAdminFactory.class, () -> mock(KeycloakAdminFactory.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(AuditEmitter.class, () -> mock(AuditEmitter.class))
                .withPropertyValues("sso.keycloak.client-secret=x")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    for (Class<?> c : new Class<?>[] {ClientAccessService.class, ClientScopeService.class,
                            JoinKeyService.class, InstitutionContextService.class, MfaService.class,
                            MfaFlowInstaller.class, Mailer.class, SetupLinkService.class,
                            ServiceAccountRoleReconciler.class}) {
                        assertThat(ctx).hasSingleBean(c);
                    }
                    assertThat(ctx.getBean(DelegatedPermissionsHolder.class).available()).isTrue();
                    assertThat(ctx.getBean(Mailer.class).configured()).isFalse();
                    assertThat(ctx.getBean(ClientAccessService.class).defaultDeny()).isTrue();
                });
    }
}
