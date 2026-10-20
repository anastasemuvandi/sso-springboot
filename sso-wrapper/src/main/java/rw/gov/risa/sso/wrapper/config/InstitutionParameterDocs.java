package rw.gov.risa.sso.wrapper.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;

import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.service.institution.CurrentInstitution;

/**
 * Documents {@code @CurrentInstitution InstitutionContext} arguments as what the resolver really reads —
 * an optional {@code ?institution=} query parameter — instead of letting springdoc describe the
 * resolved context object as a required query object.
 */
@Configuration
public class InstitutionParameterDocs {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(InstitutionContext.class);
    }

    @Bean
    OperationCustomizer institutionQueryParameter() {
        return (operation, handler) -> {
            for (MethodParameter p : handler.getMethodParameters()) {
                CurrentInstitution spec = p.getParameterAnnotation(CurrentInstitution.class);
                if (spec == null) {
                    continue;
                }
                boolean present = operation.getParameters() != null && operation.getParameters().stream()
                        .anyMatch(x -> spec.param().equals(x.getName()));
                if (!present) {
                    operation.addParametersItem(new Parameter().in("query").name(spec.param()).required(false)
                            .description("Institution slug. Needed only when the caller administers several "
                                    + "institutions or is a platform admin acting for one; it never widens "
                                    + "a delegated admin's scope.")
                            .schema(new StringSchema()));
                }
            }
            return operation;
        };
    }
}
