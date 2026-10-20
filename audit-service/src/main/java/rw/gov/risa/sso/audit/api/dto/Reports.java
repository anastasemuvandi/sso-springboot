package rw.gov.risa.sso.audit.api.dto;

import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response shapes of the {@code /audit/reports/*} JSON endpoints (reference routes/health.py). */
public final class Reports {

    private Reports() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "CertExpiryReport")
    public record CertExpiryReport(int daysAhead, int total, List<CertExpiryItem> expiring) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "CertExpiryItem")
    public record CertExpiryItem(String userId, String employeeId, String email,
                                 @Schema(description = "YYYY-MM-DD") String certExpiry, Integer daysRemaining) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "AccessAnalytics")
    public record AccessAnalytics(List<AppCount> topApps, List<DayCount> failedLoginTrend, long totalAccessDenials) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "AppAccessCount")
    public record AppCount(String appId, long accessCount) {
    }

    @Schema(name = "DayCount")
    public record DayCount(@Schema(description = "YYYY-MM-DD (UTC)") String day, long count) {
    }
}
