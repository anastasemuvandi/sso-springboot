package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One person as Keycloak is able to hold them. {@code institutionCode} is the raw upstream
 * employer id, NOT a slug. {@code ambiguousReason} set means {@code institutionCode} is null
 * on purpose. {@code allInstitutionCodes} lists every employer, primary first.
 */
public record PersonView(
        String employeeNumber,
        String nationalId,
        String phone,
        String email,
        String firstName,
        String lastName,
        String institutionCode,
        String position,
        String status,
        String ambiguousReason,
        List<String> allInstitutionCodes) {

    public PersonView {
        status = status == null ? IppisPersonViews.STATUS_ACTIVE : status;
        allInstitutionCodes = allInstitutionCodes == null ? List.of() : List.copyOf(allInstitutionCodes);
    }

    public boolean isLeaver() {
        return IppisPersonViews.STATUS_TERMINATED.equals(status);
    }

    /** True only for {@code multiple_primary_employments}; {@code no_employment} is not ambiguity. */
    public boolean isAmbiguous() {
        return IppisPersonViews.AMBIGUOUS_MULTIPLE_PRIMARY.equals(ambiguousReason);
    }

    public String displayName() {
        String name = Stream.of(firstName, lastName)
                .filter(p -> p != null && !p.isEmpty())
                .collect(Collectors.joining(" ")).strip();
        return name.isEmpty() ? employeeNumber : name;
    }
}
