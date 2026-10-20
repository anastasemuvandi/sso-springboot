package rw.gov.risa.sso.audit.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.audit.api.dto.ApiError;
import rw.gov.risa.sso.audit.config.AuditProperties;

/**
 * Every {@code /audit/**} route requires {@code X-Audit-API-Key} (reference
 * {@code middleware/api_key.py}). Missing and wrong keys both get 401 with the error envelope.
 * Keys are compared in constant time over their SHA-256 digests, so neither content nor length
 * leaks through timing. {@code /health}, {@code /actuator/**} and the OpenAPI document are open
 * (the service is only reachable on the internal network).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Audit-API-Key";

    private final List<byte[]> keyDigests;
    private final ObjectMapper mapper;

    public ApiKeyFilter(AuditProperties props, ObjectMapper mapper) {
        this.keyDigests = props.acceptedKeys().stream().map(ApiKeyFilter::sha256).toList();
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/audit") || path.startsWith("/audit/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matches(request.getHeader(HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    new ApiError("unauthorized", "Invalid or missing X-Audit-API-Key", Map.of()));
            return;
        }
        chain.doFilter(request, response);
    }

    boolean matches(String presented) {
        if (presented == null || presented.isEmpty() || keyDigests.isEmpty()) {
            return false;
        }
        byte[] digest = sha256(presented);
        boolean ok = false;
        for (byte[] k : keyDigests) {
            ok |= MessageDigest.isEqual(k, digest);
        }
        return ok;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
