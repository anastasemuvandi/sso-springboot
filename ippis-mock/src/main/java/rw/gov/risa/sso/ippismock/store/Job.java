package rw.gov.risa.sso.ippismock.store;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One posting, in the GESB feed's own camelCase vocabulary. {@code endDate == null} means a
 * current posting. Absent fields are omitted from the wire, never sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Job(
        String id,
        String positionId,
        String positionName,
        String unitId,
        String unitName,
        String level,
        Boolean hasSubordinates,
        String employeeGroup,
        @JsonProperty("isSecondaryEmployment") boolean isSecondaryEmployment,
        @JsonProperty("isActing") Boolean isActing,
        String startDate,
        String endDate,
        String reasonForChange,
        String createdAt,
        String updatedAt) {

    @JsonIgnore
    public boolean isCurrent() {
        return endDate == null;
    }

    public Job closed(String end, String stamp) {
        return new Job(id, positionId, positionName, unitId, unitName, level, hasSubordinates, employeeGroup,
                isSecondaryEmployment, isActing, startDate, end, "Removed", createdAt, stamp);
    }
}
