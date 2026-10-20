package rw.gov.risa.sso.wrapper.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis-backed (Bucket4j) limit on a controller method, shared across instances.
 * Example: {@code @RateLimit(value = "30/minute", key = RateLimit.Key.CALLER)}.
 * Every route also has the global default limit (RATE_LIMIT_DEFAULT, per IP).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** "{count}/{second|minute|hour|day}". */
    String value();

    Key key() default Key.CALLER_OR_IP;

    /** Bucket name; defaults to the handler method. Share a name to share a bucket across routes. */
    String name() default "";

    enum Key {
        /** Remote IP. */
        IP,
        /** Authenticated username, or the IP when unauthenticated. */
        CALLER_OR_IP,
        /** Authenticated username only (unauthenticated requests share one bucket). */
        CALLER
    }
}
