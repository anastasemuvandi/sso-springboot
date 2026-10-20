package rw.gov.risa.sso.wrapper.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Auth-sensitive responses must never be cached by intermediaries. */
@Component
public class NoStoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/auth/") || path.startsWith("/oauth2/") || path.startsWith("/account/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
