package rw.gov.risa.sso.wrapper.service.institution;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.ErrorEnvelope;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionRefusedException;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.security.Callers;

/**
 * Spring MVC wiring for the delegated surface:
 * <ul>
 *   <li>the {@link CurrentInstitution} argument resolver;</li>
 *   <li>a fail-closed interceptor: every {@code /institution-admin/**} request answers 503
 *       {@code delegation_unavailable} while the templates are missing or unsafe;</li>
 *   <li>an advice mapping {@link InstitutionRefusedException} (thrown by the policy record's own
 *       {@code assert*} methods, e.g. {@code ctx.assertClientInScope(..)}) onto the error envelope.</li>
 * </ul>
 */
@Configuration
public class InstitutionWebConfig implements WebMvcConfigurer {

    public static final String DELEGATED_PATHS = "/institution-admin/**";

    private final InstitutionContextService contexts;
    private final DelegatedPermissionsHolder templates;

    public InstitutionWebConfig(InstitutionContextService contexts, DelegatedPermissionsHolder templates) {
        this.contexts = contexts;
        this.templates = templates;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new InstitutionContextArgumentResolver(contexts));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DelegationAvailableInterceptor(templates)).addPathPatterns(DELEGATED_PATHS);
    }

    /** Resolves {@code @CurrentInstitution InstitutionContext} parameters. */
    public static final class InstitutionContextArgumentResolver implements HandlerMethodArgumentResolver {

        private final InstitutionContextService contexts;

        public InstitutionContextArgumentResolver(InstitutionContextService contexts) {
            this.contexts = contexts;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentInstitution.class)
                    && InstitutionContext.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                      WebDataBinderFactory binderFactory) {
            CurrentInstitution spec = parameter.getParameterAnnotation(CurrentInstitution.class);
            CallerPrincipal caller = Callers.current()
                    .orElseThrow(() -> ApiException.unauthorized("Authentication required"));
            String requested = request.getParameter(spec == null ? "institution" : spec.param());
            return contexts.resolve(caller, requested, spec == null ? new rw.gov.risa.sso.wrapper.policy.delegated.Capability[0]
                    : spec.require());
        }
    }

    /** 503 {@code delegation_unavailable} on every delegated route while the templates are unusable. */
    public static final class DelegationAvailableInterceptor implements HandlerInterceptor {

        private final DelegatedPermissionsHolder templates;

        public DelegationAvailableInterceptor(DelegatedPermissionsHolder templates) {
            this.templates = templates;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!templates.available()) {
                throw ApiException.unavailable(ErrorCodes.DELEGATION_UNAVAILABLE,
                        "Delegated permission templates are not loaded, so institution admin operations are refused. "
                                + templates.unavailableReason().orElse(""));
            }
            return true;
        }
    }

    /** Maps the policy module's own refusal exception (runs before the global catch-all). */
    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class InstitutionRefusalAdvice {
        @ExceptionHandler(InstitutionRefusedException.class)
        public ResponseEntity<ErrorEnvelope> refused(InstitutionRefusedException e) {
            ApiException api = InstitutionContextService.toApiException(e);
            return ResponseEntity.status(api.status()).body(new ErrorEnvelope(api.error(), api.getMessage(),
                    new java.util.LinkedHashMap<>(api.details())));
        }
    }
}
