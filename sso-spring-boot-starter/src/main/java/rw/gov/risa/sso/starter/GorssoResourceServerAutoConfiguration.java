package rw.gov.risa.sso.starter;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import rw.gov.risa.sso.starter.config.GorssoProperties;
import rw.gov.risa.sso.starter.joinkey.JoinKeyColumn;
import rw.gov.risa.sso.starter.joinkey.JoinKeyResolver;
import rw.gov.risa.sso.starter.jwt.GorssoJwtDecoders;
import rw.gov.risa.sso.starter.roles.ClientRoleMapper;
import rw.gov.risa.sso.starter.roles.GorssoAuthoritiesConverter;
import rw.gov.risa.sso.starter.roles.GorssoAuthorityMapper;
import rw.gov.risa.sso.starter.roles.GorssoJwtAuthenticationConverter;
import rw.gov.risa.sso.starter.roles.GorssoSecurityExpressions;
import rw.gov.risa.sso.starter.roles.InstitutionRoleMapper;
import rw.gov.risa.sso.starter.roles.InstitutionRolesResolver;
import rw.gov.risa.sso.starter.roles.ScopeAuthorityMapper;

/**
 * GoRSSO resource-server beans for an integrated app: the RS256 {@link JwtDecoder}
 * (issuer + JWKS + azp), the authority mappers and converters, the {@code join_key} resolver and
 * the {@code gorsso} SpEL bean. Every bean backs off when the app declares its own.
 *
 * <p>Opt-outs: {@code gorsso.enabled=false} (everything), {@code gorsso.resource-server.enabled},
 * {@code gorsso.join-key.enabled}, {@code gorsso.roles.institution.enabled},
 * {@code gorsso.roles.client.enabled}, {@code gorsso.roles.scopes.enabled}.
 */
@AutoConfiguration(before = {OAuth2ResourceServerAutoConfiguration.class, SecurityAutoConfiguration.class})
@ConditionalOnClass(JwtDecoder.class)
@ConditionalOnProperty(prefix = "gorsso", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GorssoProperties.class)
public class GorssoResourceServerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(JwtDecoder.class)
  @ConditionalOnProperty(prefix = "gorsso", name = "issuer-uri")
  @ConditionalOnProperty(prefix = "gorsso.resource-server", name = "enabled", havingValue = "true", matchIfMissing = true)
  JwtDecoder gorssoJwtDecoder(GorssoProperties props) {
    return GorssoJwtDecoders.create(props);
  }

  @Configuration(proxyBeanMethods = false)
  static class Mappers {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "gorsso.roles.institution", name = "enabled", havingValue = "true", matchIfMissing = true)
    InstitutionRoleMapper gorssoInstitutionRoleMapper(GorssoProperties props) {
      return new InstitutionRoleMapper(props.roles().institution().prefix());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "gorsso.roles.client", name = "enabled", havingValue = "true", matchIfMissing = true)
    ClientRoleMapper gorssoClientRoleMapper(GorssoProperties props) {
      return new ClientRoleMapper(props.effectiveClientRoleClients(), props.roles().client().prefix());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "gorsso.roles.scopes", name = "enabled", havingValue = "true", matchIfMissing = true)
    ScopeAuthorityMapper gorssoScopeAuthorityMapper() {
      return new ScopeAuthorityMapper();
    }
  }

  @Bean
  @ConditionalOnMissingBean
  GorssoAuthoritiesConverter gorssoAuthoritiesConverter(ObjectProvider<GorssoAuthorityMapper> mappers) {
    List<GorssoAuthorityMapper> ordered = mappers.orderedStream().toList();
    return new GorssoAuthoritiesConverter(ordered);
  }

  @Bean
  @ConditionalOnMissingBean
  GorssoJwtAuthenticationConverter gorssoJwtAuthenticationConverter(GorssoAuthoritiesConverter authorities) {
    return new GorssoJwtAuthenticationConverter(authorities);
  }

  @Bean
  @ConditionalOnMissingBean
  InstitutionRolesResolver institutionRolesResolver(GorssoProperties props) {
    return new InstitutionRolesResolver(props.institution().slug());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "gorsso.join-key", name = "enabled", havingValue = "true", matchIfMissing = true)
  JoinKeyResolver joinKeyResolver(GorssoProperties props) {
    return new JoinKeyResolver(props.joinKey().claim(), JoinKeyColumn.parse(props.joinKey().column()));
  }

  @Bean(name = "gorsso")
  @ConditionalOnMissingBean(name = "gorsso")
  GorssoSecurityExpressions gorsso(InstitutionRolesResolver institutions, ObjectProvider<JoinKeyResolver> joinKeys) {
    return new GorssoSecurityExpressions(institutions, joinKeys.getIfAvailable());
  }
}
