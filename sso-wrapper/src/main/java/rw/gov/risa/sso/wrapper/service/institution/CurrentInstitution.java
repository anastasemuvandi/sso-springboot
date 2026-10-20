package rw.gov.risa.sso.wrapper.service.institution;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import rw.gov.risa.sso.wrapper.policy.delegated.Capability;

/**
 * Inject the caller's resolved {@code InstitutionContext} into a controller method — resolved, or the
 * request is refused (403/400/503 envelope) before the method runs:
 *
 * <pre>{@code
 * @GetMapping("/institution-admin/apps")
 * List<AppDto> apps(@CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx) { ... }
 * }</pre>
 *
 * The institution is read from the {@code ?institution=} query parameter ({@link #param()}), needed only
 * when the caller administers several institutions or is a platform admin acting for one.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentInstitution {

    /** Capabilities the context must carry (403 {@code capability_denied} otherwise). */
    Capability[] require() default {};

    /** Query parameter naming the institution. */
    String param() default "institution";
}
