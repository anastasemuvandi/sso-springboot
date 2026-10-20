package rw.gov.risa.sso.wrapper.security;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.ErrorEnvelope;

/** 401/403 raised by the filter chain, in the same envelope as everything else. */
@Component
public class JsonSecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public JsonSecurityErrorHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, 401, new ErrorEnvelope(ErrorCodes.UNAUTHORIZED, "Invalid authentication credentials",
                Map.of()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, 403, new ErrorEnvelope(ErrorCodes.FORBIDDEN,
                "Insufficient permissions. Required: platform admin tier", Map.of()));
    }

    private void write(HttpServletResponse response, int status, ErrorEnvelope body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
