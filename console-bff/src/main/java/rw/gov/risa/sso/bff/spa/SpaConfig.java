package rw.gov.risa.sso.bff.spa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import rw.gov.risa.sso.bff.config.BffProperties;

/**
 * Serves the built SPA ({@code console-web/dist}) from {@code CONSOLE_WEB_DIST} and/or
 * {@code classpath:/static/}, with the history-API fallback: an unknown extension-less path gets
 * {@code index.html} — but NEVER under {@code /api}, {@code /bff}, {@code /oauth2}, {@code /login} or
 * {@code /actuator}, and never for a missing asset ({@code /assets/x.js} is a 404, not HTML).
 * Hashed {@code /assets/**} are cached for a year; everything else is {@code no-cache}.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {
    static final List<String> NO_FALLBACK = List.of("api", "bff", "oauth2", "login", "actuator");

    private final String[] locations;

    public SpaConfig(BffProperties properties) {
        List<String> l = new ArrayList<>();
        if (properties.spaDir() != null && !properties.spaDir().isBlank()) {
            String dir = properties.spaDir();
            l.add("file:" + (dir.endsWith("/") ? dir : dir + "/"));
        }
        l.add("classpath:/static/");
        this.locations = l.toArray(String[]::new);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(withSuffix("assets/"))
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .resourceChain(true)
                .addResolver(new PathResourceResolver());
        registry.addResourceHandler("/**")
                .addResourceLocations(locations)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    private String[] withSuffix(String suffix) {
        String[] out = new String[locations.length];
        for (int i = 0; i < locations.length; i++) {
            out[i] = locations[i] + suffix;
        }
        return out;
    }

    static boolean fallbackAllowed(String resourcePath) {
        String p = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        String first = p.contains("/") ? p.substring(0, p.indexOf('/')) : p;
        if (NO_FALLBACK.contains(first.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String last = p.substring(p.lastIndexOf('/') + 1);
        return !last.contains("."); // a missing file stays a 404
    }

    static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource found = super.getResource(resourcePath, location);
            if (found != null) {
                return found;
            }
            if (!fallbackAllowed(resourcePath)) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }
    }
}
