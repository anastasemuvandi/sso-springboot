package rw.gov.risa.sso.wrapper.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Remote IP of the current request. X-Forwarded-For is honoured only through
 * {@code server.forward-headers-strategy=framework} (i.e. by the trusted proxy configuration),
 * never parsed ad hoc here.
 */
public final class RequestInfo {
    private RequestInfo() {}

    public static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return clientIp(attrs.getRequest());
        }
        return null;
    }

    public static String clientIp(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }
}
