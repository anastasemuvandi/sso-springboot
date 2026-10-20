package rw.gov.risa.sso.wrapper.identity;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The PERSON. {@code id} IS the IPPIS employee number (natural key). */
@Entity
@Table(name = "employee")
public class EmployeeEntity {
    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "rssb_number")
    private String rssbNumber;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "gender")
    private String gender;

    @Column(name = "marital_status")
    private String maritalStatus;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "source_created_at")
    private OffsetDateTime sourceCreatedAt;

    @Column(name = "source_updated_at")
    private OffsetDateTime sourceUpdatedAt;

    protected EmployeeEntity() {
    }

    public String getId() {
        return id;
    }

    public String getNationalId() {
        return nationalId;
    }

    public String getRssbNumber() {
        return rssbNumber;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getGender() {
        return gender;
    }

    public String getMaritalStatus() {
        return maritalStatus;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public OffsetDateTime getSourceCreatedAt() {
        return sourceCreatedAt;
    }

    public OffsetDateTime getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }
}
