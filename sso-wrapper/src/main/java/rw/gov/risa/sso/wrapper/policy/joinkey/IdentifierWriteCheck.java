package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The write gate for user identifiers: a user write that sets NID, phone or email must store a
 * usable value — <b>400</b> malformed, <b>409</b> already held by another account (naming neither
 * the account nor its owner). Applied on the shared user write path for platform and delegated
 * admins; <b>not</b> applied to the IPPIS reconcile (IPPIS is authoritative).
 *
 * <p>Service flow: {@link #candidates} → for each: {@link #formatRefusal} (when format validation
 * is on) → search holders by {@link ColumnSpec#searchField()} (a failed search is skipped, never a
 * refusal) → {@link #duplicateRefusal}.
 */
public final class IdentifierWriteCheck {

    private IdentifierWriteCheck() {
    }

    /** The three identifier columns, checked whatever column any client is configured for. */
    public static final List<JoinColumn> IDENTIFIER_COLUMNS = List.of(JoinColumn.NID, JoinColumn.PHONE,
            JoinColumn.EMAIL);

    /** One identifier value a user write is setting. */
    public record Candidate(JoinColumn column, String value) {
    }

    /**
     * A refusal.
     *
     * @param status  400 (malformed) or 409 (already held)
     * @param message the detail
     */
    public record Refusal(int status, String message) {
    }

    /**
     * The identifier values a user write is setting, in Keycloak's own shapes.
     *
     * @param emailPresent whether the write body carries the {@code email} key at all
     * @param email        its value (when present)
     * @param attributes   the body's {@code attributes} (may be {@code null}); only keys present are checked
     */
    public static List<Candidate> candidates(boolean emailPresent, String email,
                                             Map<String, ? extends Collection<String>> attributes) {
        List<Candidate> found = new ArrayList<>();
        for (JoinColumn column : IDENTIFIER_COLUMNS) {
            ColumnSpec spec = JoinKeys.specFor(column);
            String raw;
            if (spec.isProperty()) {
                if (!emailPresent) {
                    continue;
                }
                raw = email;
            } else {
                if (attributes == null || !attributes.containsKey(spec.source())) {
                    continue;
                }
                Collection<String> values = attributes.get(spec.source());
                raw = values == null || values.isEmpty() ? "" : values.iterator().next();
            }
            String value = raw == null ? "" : raw.strip();
            if (!value.isEmpty()) {
                found.add(new Candidate(column, value));
            }
        }
        return found;
    }

    /** 400 when the value is malformed. */
    public static Optional<Refusal> formatRefusal(Candidate candidate) {
        ColumnSpec spec = JoinKeys.specFor(candidate.column());
        return spec.validate(candidate.value()).map(problem -> new Refusal(400, problem + " It is stored as the "
                + "user attribute '" + spec.source() + "' and a client may authenticate on it, so it has to be "
                + "usable as a key."));
    }

    /**
     * 409 when another account (not {@code selfUserId}) holds the same normalised value.
     *
     * @param holders the users a lookup returned
     * @param selfUserId the account being updated ({@code null} on create)
     */
    public static Optional<Refusal> duplicateRefusal(Candidate candidate, Collection<JoinKeyUser> holders,
                                                     String selfUserId) {
        ColumnSpec spec = JoinKeys.specFor(candidate.column());
        String mine = spec.normalise(candidate.value());
        if (holders != null) {
            for (JoinKeyUser h : holders) {
                if (h == null || h.id() == null || h.id().isEmpty() || h.id().equals(selfUserId)) {
                    continue;
                }
                if (spec.normalise(spec.read(h)).equals(mine)) {
                    return Optional.of(new Refusal(409, spec.label() + " '" + candidate.value() + "' is already on "
                            + "another account. It has to identify exactly one person — a client that "
                            + "authenticates on '" + spec.source() + "' cannot tell two accounts sharing it apart."));
                }
            }
        }
        return Optional.empty();
    }
}
