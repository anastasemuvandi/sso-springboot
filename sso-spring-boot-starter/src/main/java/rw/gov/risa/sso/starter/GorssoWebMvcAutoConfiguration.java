package rw.gov.risa.sso.starter;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import rw.gov.risa.sso.starter.joinkey.JoinKeyResolver;
import rw.gov.risa.sso.starter.roles.InstitutionRolesResolver;
import rw.gov.risa.sso.starter.web.GorssoErrorAdvice;
import rw.gov.risa.sso.starter.web.InstitutionRolesArgumentResolver;
import rw.gov.risa.sso.starter.web.JoinKeyArgumentResolver;

/** {@code @JoinKey} and {@code InstitutionRoles} controller arguments + the error envelope. */
@AutoConfiguration(after = GorssoResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnProperty(prefix = "gorsso", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GorssoWebMvcAutoConfiguration {

  @Bean
  WebMvcConfigurer gorssoArgumentResolvers(
      ObjectProvider<JoinKeyResolver> joinKeys, ObjectProvider<InstitutionRolesResolver> institutions) {
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        joinKeys.ifAvailable(r -> resolvers.add(new JoinKeyArgumentResolver(r)));
        institutions.ifAvailable(r -> resolvers.add(new InstitutionRolesArgumentResolver(r)));
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "gorsso.join-key", name = "enabled", havingValue = "true", matchIfMissing = true)
  GorssoErrorAdvice gorssoErrorAdvice() {
    return new GorssoErrorAdvice();
  }
}
