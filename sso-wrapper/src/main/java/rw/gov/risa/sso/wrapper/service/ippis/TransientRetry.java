package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Port of {@code core/http_retry.py} on Resilience4j: retry network errors and 5xx/429 with LINEAR
 * backoff (attempt n waits n x base); a non-429 4xx is permanent and rethrown at once. Shared by
 * the IPPIS reads and the Keycloak writes of the reconcile so a transient blip neither marks a
 * user as errored nor aborts a batch.
 */
public final class TransientRetry {

    private final Retry retry;

    public TransientRetry(String name, int maxAttempts, Duration backoffBase) {
        long base = Math.max(0, backoffBase.toMillis());
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(Math.max(1, maxAttempts))
                .intervalFunction((IntervalFunction) attempt -> base * attempt)
                .retryOnException(TransientRetry::isTransient)
                .failAfterMaxAttempts(false)
                .build();
        this.retry = Retry.of(name, cfg);
    }

    public <T> T call(Supplier<T> op) {
        return Retry.decorateSupplier(retry, op).get();
    }

    public void run(Runnable op) {
        call(() -> {
            op.run();
            return null;
        });
    }

    public static boolean isTransientStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    /** Network/timeout errors and 5xx/429 responses, from either the IPPIS RestClient or the Keycloak client. */
    public static boolean isTransient(Throwable t) {
        if (t instanceof HttpStatusCodeException h) {
            return isTransientStatus(h.getStatusCode().value());
        }
        if (t instanceof ResourceAccessException) {
            return true;
        }
        if (t instanceof jakarta.ws.rs.WebApplicationException w) {
            return w.getResponse() != null && isTransientStatus(w.getResponse().getStatus());
        }
        return t instanceof jakarta.ws.rs.ProcessingException;
    }
}
