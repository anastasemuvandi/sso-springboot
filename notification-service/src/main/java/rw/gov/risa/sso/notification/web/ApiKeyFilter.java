package rw.gov.risa.sso.notification.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import rw.gov.risa.sso.notification.config.NotificationProperties;

/**
 * Reference {@code require_api_key}: every {@code /alerts}, {@code /notifications},
 * {@code /config} (and the port's {@code /reports}) call needs
 * {@code X-Notification-API-Key: <NOTIFICATION_API_KEY>}; anything else answers 401.
 * {@code /health}, actuator and the OpenAPI docs are open, as in the reference.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Notification-API-Key";
    private static final List<String> PROTECTED = List.of("/alerts", "/notifications", "/config", "/reports");

    private final byte[] expected;
    private final ObjectMapper mapper;

    public ApiKeyFilter(NotificationProperties props, ObjectMapper mapper) {
        this.expected = props.apiKey() == null ? new byte[0] : props.apiKey().getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PROTECTED.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null || expected.length == 0
                || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    ApiError.of("unauthorized", "Invalid or missing " + HEADER));
            return;
        }
        chain.doFilter(request, response);
    }
}
