package rw.gov.risa.sso.ippismock.store;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One person in the mock's store.
 *
 * <p>The flat fields are the reference mock's record ({@code /employees} shape). {@code employments}
 * is {@code null} for a flat-only record — the reference's only kind — and {@code /people} then
 * synthesises one employment with one job exactly as the reference does. A person seeded or PUT
 * with real employments (dual appointees, movers, ambiguous primaries) carries them here, and the
 * flat projection is DERIVED from them with the wrapper's person-view rules.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredPerson {
    private String employeeNumber;
    private String nationalId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String institution;
    private String position;
    private String status = "active";
    private String updatedAt;
    private List<Employment> employments;
    private String gender;
    private String maritalStatus;
    private String dateOfBirth;
    private String createdAt;

    public StoredPerson() {
    }

    public StoredPerson copy() {
        StoredPerson p = new StoredPerson();
        p.employeeNumber = employeeNumber;
        p.nationalId = nationalId;
        p.firstName = firstName;
        p.lastName = lastName;
        p.email = email;
        p.phone = phone;
        p.institution = institution;
        p.position = position;
        p.status = status;
        p.updatedAt = updatedAt;
        p.employments = employments == null ? null : new ArrayList<>(employments);
        p.gender = gender;
        p.maritalStatus = maritalStatus;
        p.dateOfBirth = dateOfBirth;
        p.createdAt = createdAt;
        return p;
    }

    @JsonIgnore
    public boolean isNested() {
        return employments != null;
    }

    public String getEmployeeNumber() { return employeeNumber; }
    public void setEmployeeNumber(String v) { employeeNumber = v; }
    public String getNationalId() { return nationalId; }
    public void setNationalId(String v) { nationalId = v; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String v) { firstName = v; }
    public String getLastName() { return lastName; }
    public void setLastName(String v) { lastName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { phone = v; }
    public String getInstitution() { return institution; }
    public void setInstitution(String v) { institution = v; }
    public String getPosition() { return position; }
    public void setPosition(String v) { position = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String v) { updatedAt = v; }
    public List<Employment> getEmployments() { return employments; }
    public void setEmployments(List<Employment> v) { employments = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { gender = v; }
    public String getMaritalStatus() { return maritalStatus; }
    public void setMaritalStatus(String v) { maritalStatus = v; }
    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String v) { dateOfBirth = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { createdAt = v; }
}
