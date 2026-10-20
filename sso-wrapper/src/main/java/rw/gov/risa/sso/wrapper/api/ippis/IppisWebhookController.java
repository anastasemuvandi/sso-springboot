package rw.gov.risa.sso.wrapper.api.ippis;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.service.ippis.IppisWebhookService;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * {@code POST /ippis/events} — the signed IPPIS push. Public (no bearer): trust comes from the HMAC
 * over {@code X-IPPIS-Timestamp + raw body}, verified over the EXACT received bytes.
 */
@RestController
@Tag(name = "IPPIS")
public class IppisWebhookController {

    private final IppisWebhookService webhook;

    public IppisWebhookController(IppisWebhookService webhook) {
        this.webhook = webhook;
    }

    @PostMapping(path = "/ippis/events", consumes = MediaType.ALL_VALUE)
    @RateLimit(value = "600/minute", key = RateLimit.Key.IP)
    @Operation(summary = "Receive a signed IPPIS lifecycle event (employee.terminated / employee.updated)")
    public Map<String, Object> receive(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = IppisWebhookService.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(name = IppisWebhookService.TIMESTAMP_HEADER, required = false) String timestamp) {
        return webhook.receive(body, signature, timestamp, RequestInfo.clientIp());
    }
}
