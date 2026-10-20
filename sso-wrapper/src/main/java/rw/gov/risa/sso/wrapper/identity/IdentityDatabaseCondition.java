package rw.gov.risa.sso.wrapper.identity;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@code sso.identity.database-url} (IDENTITY_DATABASE_URL) is non-blank.
 * {@code @ConditionalOnProperty} is not enough: it treats an empty string as "set".
 */
public class IdentityDatabaseCondition implements Condition {

    public static final String PROPERTY = "sso.identity.database-url";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String url = context.getEnvironment().getProperty(PROPERTY);
        return url != null && !url.isBlank();
    }
}
