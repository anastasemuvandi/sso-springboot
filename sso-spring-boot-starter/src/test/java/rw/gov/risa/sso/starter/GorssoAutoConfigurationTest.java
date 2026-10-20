package rw.gov.risa.sso.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import rw.gov.risa.sso.starter.config.GorssoProperties;
import rw.gov.risa.sso.starter.joinkey.JoinKeyColumn;
import rw.gov.risa.sso.starter.joinkey.JoinKeyResolver;
import rw.gov.risa.sso.starter.roles.ClientRoleMapper;
import rw.gov.risa.sso.starter.roles.GorssoAuthoritiesConverter;
import rw.gov.risa.sso.starter.roles.GorssoJwtAuthenticationConverter;
import rw.gov.risa.sso.starter.roles.GorssoSecurityExpressions;
import rw.gov.risa.sso.starter.roles.InstitutionRoleMapper;
import rw.gov.risa.sso.starter.roles.InstitutionRolesResolver;
import rw.gov.risa.sso.starter.roles.ScopeAuthorityMapper;
import rw.gov.risa.sso.starter.web.GorssoErrorAdvice;

class GorssoAutoConfigurationTest {

  static final String[] MINIMAL = {
    "gorsso.issuer-uri=" + TestTokens.ISSUER, "gorsso.client-id=minagri-kubaka-web"
  };

  private final WebApplicationContextRunner web =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  GorssoResourceServerAutoConfiguration.class,
                  GorssoWebSecurityAutoConfiguration.class,
                  GorssoWebMvcAutoConfiguration.class,
                  OAuth2ResourceServerAutoConfiguration.class,
                  SecurityAutoConfiguration.class));

  private final ApplicationContextRunner plain =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  GorssoResourceServerAutoConfiguration.class,
                  GorssoWebSecurityAutoConfiguration.class,
                  GorssoWebMvcAutoConfiguration.class));

  @Test
  void minimalConfigurationWiresEverything() {
    web.withPropertyValues(MINIMAL)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(GorssoProperties.class);
              assertThat(ctx.getBean(JwtDecoder.class)).isInstanceOf(NimbusJwtDecoder.class);
              assertThat(ctx).hasSingleBean(InstitutionRoleMapper.class);
              assertThat(ctx).hasSingleBean(ClientRoleMapper.class);
              assertThat(ctx).hasSingleBean(ScopeAuthorityMapper.class);
              assertThat(ctx).hasSingleBean(GorssoAuthoritiesConverter.class);
              assertThat(ctx).hasSingleBean(GorssoJwtAuthenticationConverter.class);
              assertThat(ctx).hasSingleBean(InstitutionRolesResolver.class);
              assertThat(ctx).hasSingleBean(JoinKeyResolver.class);
              assertThat(ctx.getBean("gorsso")).isInstanceOf(GorssoSecurityExpressions.class);
              assertThat(ctx).hasBean("gorssoSecurityFilterChain");
              assertThat(ctx).hasSingleBean(SecurityFilterChain.class);
              assertThat(ctx).hasBean("gorssoArgumentResolvers");
              assertThat(ctx).hasSingleBean(GorssoErrorAdvice.class);
              assertThat(ctx.getBean(JoinKeyResolver.class).column()).isEqualTo(JoinKeyColumn.EMAIL);
            });
  }

  @Test
  void withoutIssuerThereIsNoDecoderAndNoGorssoChain() {
    web.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).doesNotHaveBean(JwtDecoder.class);
          assertThat(ctx).doesNotHaveBean("gorssoSecurityFilterChain");
          // the claim helpers still work (e.g. for an app that builds its own decoder)
          assertThat(ctx).hasSingleBean(JoinKeyResolver.class);
          assertThat(ctx).hasSingleBean(GorssoJwtAuthenticationConverter.class);
        });
  }

  @Test
  void issuerWithoutClientIdFailsFast() {
    web.withPropertyValues("gorsso.issuer-uri=" + TestTokens.ISSUER)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("gorsso.client-id");
            });
  }

  @Test
  void masterSwitchTurnsEverythingOff() {
    web.withPropertyValues(MINIMAL)
        .withPropertyValues("gorsso.enabled=false")
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(GorssoProperties.class);
              assertThat(ctx).doesNotHaveBean(GorssoJwtAuthenticationConverter.class);
              assertThat(ctx).doesNotHaveBean("gorssoSecurityFilterChain");
              assertThat(ctx).doesNotHaveBean("gorssoArgumentResolvers");
            });
  }

  @Test
  void individualOptOuts() {
    web.withPropertyValues(MINIMAL)
        .withPropertyValues(
            "gorsso.roles.institution.enabled=false",
            "gorsso.roles.client.enabled=false",
            "gorsso.roles.scopes.enabled=false",
            "gorsso.join-key.enabled=false")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(InstitutionRoleMapper.class);
              assertThat(ctx).doesNotHaveBean(ClientRoleMapper.class);
              assertThat(ctx).doesNotHaveBean(ScopeAuthorityMapper.class);
              assertThat(ctx).doesNotHaveBean(JoinKeyResolver.class);
              assertThat(ctx).doesNotHaveBean(GorssoErrorAdvice.class);
              assertThat(ctx).hasSingleBean(GorssoAuthoritiesConverter.class);
            });
  }

  @Test
  void resourceServerOptOutDropsDecoderAndChain() {
    web.withPropertyValues(MINIMAL)
        .withPropertyValues("gorsso.resource-server.enabled=false")
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(JwtDecoder.class);
              assertThat(ctx).doesNotHaveBean("gorssoSecurityFilterChain");
            });
  }

  @Test
  void defaultChainOptOutKeepsDecoder() {
    web.withPropertyValues(MINIMAL)
        .withPropertyValues("gorsso.resource-server.default-chain=false")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(JwtDecoder.class);
              assertThat(ctx).doesNotHaveBean("gorssoSecurityFilterChain");
            });
  }

  @Test
  void backsOffForUserBeans() {
    web.withPropertyValues(MINIMAL)
        .withUserConfiguration(UserBeans.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(JwtDecoder.class)).isSameAs(ctx.getBean(UserBeans.class).decoder);
              assertThat(ctx).doesNotHaveBean("gorssoJwtDecoder");
              assertThat(ctx).doesNotHaveBean("gorssoSecurityFilterChain");
              assertThat(ctx).hasBean("userChain");
              assertThat(ctx.getBean(JoinKeyResolver.class).column()).isEqualTo(JoinKeyColumn.NID);
            });
  }

  @Test
  void joinKeyColumnFromProperties() {
    web.withPropertyValues(MINIMAL)
        .withPropertyValues("gorsso.join-key.column=phone")
        .run(ctx -> assertThat(ctx.getBean(JoinKeyResolver.class).column()).isEqualTo(JoinKeyColumn.PHONE));
  }

  @Test
  void nonWebApplicationGetsNoServletParts() {
    plain.withPropertyValues(MINIMAL)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(JwtDecoder.class);
              assertThat(ctx).doesNotHaveBean("gorssoSecurityFilterChain");
              assertThat(ctx).doesNotHaveBean(WebMvcConfigurer.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class UserBeans {
    final JwtDecoder decoder = token -> {
      throw new UnsupportedOperationException();
    };

    @Bean
    JwtDecoder myDecoder() {
      return decoder;
    }

    @Bean
    SecurityFilterChain userChain(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }

    @Bean
    JoinKeyResolver myJoinKeys() {
      return new JoinKeyResolver("join_key", JoinKeyColumn.NID);
    }
  }
}
