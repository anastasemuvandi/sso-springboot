package rw.gov.risa.sso.ippismock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import rw.gov.risa.sso.ippismock.api.ApiKeyInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ApiKeyInterceptor apiKeyInterceptor;

    public WebConfig(ApiKeyInterceptor apiKeyInterceptor) {
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/employees", "/employees/**", "/people", "/people/**", "/_mock/**");
    }
}
