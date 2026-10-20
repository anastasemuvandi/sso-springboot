package rw.gov.risa.sso.wrapper.service.ippis;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;

/**
 * Pull client for the IPPIS (GESB) read API ({@code ippis_client.py}). Real vs mock differs only by
 * configuration. One RestClient (connection keep-alive) for every call; connect/read timeouts =
 * IPPIS_SYNC_TIMEOUT; transient failures retried by {@link TransientRetry}; a 404 is a definitive
 * "no such employee" and is never retried.
 */
@Component
public class IppisClient {

    private static final Logger log = LoggerFactory.getLogger(IppisClient.class);

    private final IppisProperties props;
    private final RestClient http;
    private final IppisAuth auth;
    private final TransientRetry retry;

    public IppisClient(IppisProperties props, RestClient.Builder builder) {
        this.props = props;
        HttpClient.Builder jdk = HttpClient.newBuilder().connectTimeout(props.timeout());
        SSLContext ssl = sslContext(props.auth());
        if (ssl != null) {
            jdk.sslContext(ssl);
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk.build());
        factory.setReadTimeout(props.timeout());
        this.http = builder.clone().requestFactory(factory).build();
        this.auth = IppisAuth.from(props, builder.clone().requestFactory(factory));
        this.retry = new TransientRetry("ippis-read", props.effectiveMaxAttempts(), props.retryBackoff());
        if (props.baseUrl() != null && props.baseUrl().startsWith("http://")) {
            log.info("IPPIS base URL is plain http — acceptable for the dev mock only; production must use https");
        }
    }

    /** One person, nested ({@code GET /people/{n}}); empty on a definitive 404. */
    public Optional<IppisPerson> getPerson(String employeeNumber) {
        return retry.call(() -> {
            try {
                JsonNode body = http.get().uri(base() + "/people/{n}", employeeNumber).headers(auth::apply)
                        .retrieve().body(JsonNode.class);
                return Optional.ofNullable(body).map(IppisJson::person);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    return Optional.empty();
                }
                throw e;
            }
        });
    }

    /** The nested delta feed ({@code GET /people?changed_since=}); {@code since == null} returns everyone. */
    public List<IppisPerson> listPeopleChangedSince(Instant since) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(base() + "/people");
        if (since != null) {
            uri.queryParam("changed_since", since.toString());
        }
        String url = uri.encode().toUriString();
        return retry.call(() -> IppisJson.people(http.get().uri(java.net.URI.create(url)).headers(auth::apply)
                .retrieve().body(JsonNode.class)));
    }

    private String base() {
        String b = props.baseUrl() == null ? "" : props.baseUrl().strip();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** CA bundle and/or client certificate (PEM path or content); null = JDK defaults. */
    static SSLContext sslContext(IppisProperties.Auth a) {
        boolean mtls = "mtls".equalsIgnoreCase(a.method() == null ? "" : a.method().strip());
        boolean ca = a.caBundle() != null && !a.caBundle().isBlank();
        if (!mtls && !ca) {
            return null;
        }
        PemSslStoreDetails keyStore = null;
        if (mtls) {
            if (a.mtlsCert() == null || a.mtlsCert().isBlank() || a.mtlsKey() == null || a.mtlsKey().isBlank()) {
                throw new IllegalStateException("IPPIS_AUTH_METHOD=mtls needs IPPIS_MTLS_CERT and IPPIS_MTLS_KEY");
            }
            keyStore = PemSslStoreDetails.forCertificate(location(a.mtlsCert())).withPrivateKey(location(a.mtlsKey()));
        }
        PemSslStoreDetails trustStore = ca ? PemSslStoreDetails.forCertificate(location(a.caBundle())) : null;
        SslBundle bundle = SslBundle.of(new PemSslStoreBundle(keyStore, trustStore));
        return bundle.createSslContext();
    }

    /** A bare filesystem path becomes a file: location; PEM content is passed through. */
    private static String location(String v) {
        String s = v.strip();
        if (s.contains("-----BEGIN") || s.contains(":")) {
            return s;
        }
        return "file:" + s;
    }
}
