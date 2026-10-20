package rw.gov.risa.sso.wrapper.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** GoRSSO's sync bookkeeping for one employee (the joiner queue reads it). Never an authority on access. */
@Entity
@Table(name = "employee_sso_link")
public class EmployeeSsoLinkEntity {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    @Column(name = "keycloak_user_id")
    private String keycloakUserId;

    @Column(name = "match_state", nullable = false)
    private String matchState;

    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @Column(name = "last_sync_status")
    private String lastSyncStatus;

    @Column(name = "last_sync_error")
    private String lastSyncError;

    protected EmployeeSsoLinkEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public String getMatchState() {
        return matchState;
    }

    public OffsetDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public OffsetDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public String getLastSyncStatus() {
        return lastSyncStatus;
    }

    public String getLastSyncError() {
        return lastSyncError;
    }
}
