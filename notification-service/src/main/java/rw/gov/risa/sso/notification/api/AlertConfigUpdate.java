package rw.gov.risa.sso.notification.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Reference {@code AlertConfigUpdate}: a partial update — only the fields present in the
 * body are applied (pydantic {@code exclude_unset}); an explicit null clears an override.
 */
@Schema(name = "AlertConfigUpdate")
public class AlertConfigUpdate {

    private final Set<String> present = new HashSet<>();
    private Boolean enabled;
    private Integer threshold;
    private Integer windowMinutes;
    private String severity;
    private List<String> channels;
    private Integer cooldownMinutes;

    @JsonIgnore
    public boolean has(String field) {
        return present.contains(field);
    }

    @JsonProperty("enabled")
    public Boolean getEnabled() {
        return enabled;
    }

    @JsonProperty("enabled")
    public void setEnabled(Boolean v) {
        present.add("enabled");
        enabled = v;
    }

    @JsonProperty("threshold")
    public Integer getThreshold() {
        return threshold;
    }

    @JsonProperty("threshold")
    public void setThreshold(Integer v) {
        present.add("threshold");
        threshold = v;
    }

    @JsonProperty("window_minutes")
    public Integer getWindowMinutes() {
        return windowMinutes;
    }

    @JsonProperty("window_minutes")
    public void setWindowMinutes(Integer v) {
        present.add("window_minutes");
        windowMinutes = v;
    }

    @JsonProperty("severity")
    @Schema(allowableValues = {"CRITICAL", "WARNING", "INFO"})
    public String getSeverity() {
        return severity;
    }

    @JsonProperty("severity")
    public void setSeverity(String v) {
        present.add("severity");
        severity = v;
    }

    @JsonProperty("channels")
    @Schema(description = "Subset of email, inapp, webhook")
    public List<String> getChannels() {
        return channels;
    }

    @JsonProperty("channels")
    public void setChannels(List<String> v) {
        present.add("channels");
        channels = v;
    }

    @JsonProperty("cooldown_minutes")
    public Integer getCooldownMinutes() {
        return cooldownMinutes;
    }

    @JsonProperty("cooldown_minutes")
    public void setCooldownMinutes(Integer v) {
        present.add("cooldown_minutes");
        cooldownMinutes = v;
    }
}
