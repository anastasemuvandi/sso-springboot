package rw.gov.risa.sso.starter.joinkey;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the caller's {@code join_key} claim to a controller parameter of type {@code String} or
 * {@link JoinKeyValue}:
 *
 * <pre>{@code
 * @GetMapping("/me") Profile me(@JoinKey String joinKey) { ... }
 * }</pre>
 *
 * When the claim is missing and {@link #required()} is true the request fails with 403
 * {@code join_key_missing} (the wrapper already refuses tokens without one at every token door,
 * so this only fires for clients whose column is unset and not enforced).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface JoinKey {
  boolean required() default true;
}
