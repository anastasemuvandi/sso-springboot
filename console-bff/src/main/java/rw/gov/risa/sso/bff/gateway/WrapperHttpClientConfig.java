package rw.gov.risa.sso.bff.gateway;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * The HTTP client the gateway relays with (picked up by Gateway Server MVC's RestClient customizer):
 * JDK HttpClient pinned to HTTP/1.1 — the default HTTP/2 client tries an h2c upgrade on plain-http
 * internal hops, which some servers answer with RST_STREAM — never following redirects (a wrapper
 * 302 goes back to the browser untouched), with explicit timeouts.
 */
@Configuration
public class WrapperHttpClientConfig {

    @Bean
    ClientHttpRequestFactory wrapperRequestFactory(
            @Value("${spring.cloud.gateway.server.webmvc.http-client.connect-timeout:5s}") Duration connectTimeout,
            @Value("${spring.cloud.gateway.server.webmvc.http-client.read-timeout:120s}") Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
