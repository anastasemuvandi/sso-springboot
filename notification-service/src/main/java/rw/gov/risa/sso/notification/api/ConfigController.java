package rw.gov.risa.sso.notification.api;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.gov.risa.sso.notification.config.NotificationConfig;
import rw.gov.risa.sso.notification.service.ConfigService;

@RestController
@RequestMapping("/config")
@Tag(name = "config")
@SecurityRequirement(name = NotificationConfig.API_KEY_SCHEME)
public class ConfigController {

    private final ConfigService configs;

    public ConfigController(ConfigService configs) {
        this.configs = configs;
    }

    @GetMapping
    @Operation(summary = "Effective configuration of every rule (DB overrides over the built-in defaults)")
    public List<AlertConfigRead> list() {
        return configs.list();
    }

    @PutMapping("/{rule_name}")
    @Operation(summary = "Override a rule's configuration (partial update; only fields present are changed)")
    public AlertConfigRead update(@PathVariable("rule_name") String ruleName, @RequestBody AlertConfigUpdate body) {
        return configs.update(ruleName, body);
    }
}
