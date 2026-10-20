package rw.gov.risa.sso.wrapper.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** IPPIS sector lookup; natural key = the IPPIS sector code. */
@Entity
@Table(name = "sector")
public class SectorEntity {
    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    protected SectorEntity() {
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
