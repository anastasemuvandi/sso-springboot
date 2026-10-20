package rw.gov.risa.sso.ippismock.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import rw.gov.risa.sso.ippismock.config.IppisMockProperties;

/**
 * Every {@code /employees}, {@code /people} and {@code /_mock} call (read or write) requires
 * {@code X-IPPIS-API-Key} equal to {@code IPPIS_API_KEY}; otherwise 401. Constant-time compare.
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-IPPIS-API-Key";

    private final byte[] expected;
    private final ObjectMapper mapper;

    public ApiKeyInterceptor(IppisMockProperties properties, ObjectMapper mapper) {
        this.expected = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String given = request.getHeader(HEADER);
        byte[] actual = (given == null ? "" : given).getBytes(StandardCharsets.UTF_8);
        if (expected.length > 0 && MessageDigest.isEqual(expected, actual)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                ErrorHandler.envelope("invalid_api_key", "invalid IPPIS API key", null));
        return false;
    }
}
