package rw.gov.risa.sso.notification.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Reference {@code {"marked_read": n}}. */
public record MarkAllReadResponse(@JsonProperty("marked_read") int markedRead) {
}
