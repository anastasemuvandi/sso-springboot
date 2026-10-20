package rw.gov.risa.sso.wrapper.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One employer IPPIS knows about. {@code shortCode} (the registry slug) and {@code onboarded} are
 * GoRSSO's and are written on INSERT only.
 */
@Entity
@Table(name = "institution")
public class InstitutionEntity {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "ippis_institution_id", nullable = false)
    private String ippisInstitutionId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "sector_id")
    private String sectorId;

    @Column(name = "source_updated_at")
    private OffsetDateTime sourceUpdatedAt;

    @Column(name = "is_onboarded", nullable = false)
    private boolean onboarded;

    @Column(name = "short_code")
    private String shortCode;

    protected InstitutionEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getIppisInstitutionId() {
        return ippisInstitutionId;
    }

    public String getName() {
        return name;
    }

    public String getSectorId() {
        return sectorId;
    }

    public OffsetDateTime getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public boolean isOnboarded() {
        return onboarded;
    }

    public String getShortCode() {
        return shortCode;
    }
}
