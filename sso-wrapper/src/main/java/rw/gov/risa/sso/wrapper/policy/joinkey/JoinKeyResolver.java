package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolving one user's key ({@link #resolve}) and the same rules over a population
 * ({@link #readiness}). Pure.
 */
public final class JoinKeyResolver {

    private JoinKeyResolver() {
    }

    /**
     * Can this user be given a token for this client? Refusals, in order: <b>missing</b> →
     * <b>invalid</b> (only when {@code validateFormat}) → <b>not unique</b> (only when
     * {@code holders != null}).
     *
     * @param holders every user a lookup of the value returned (this one included), or
     *                {@code null} to skip the uniqueness check. Each holder's value is re-checked
     *                on the normalised form (Keycloak's searches are not all exact); the user
     *                themselves (same id) is never their own duplicate.
     */
    public static JoinKeyResolution resolve(JoinColumnSetting setting, JoinKeyUser user,
                                            Collection<JoinKeyUser> holders, boolean validateFormat) {
        ColumnSpec spec = setting.spec();
        String value = spec.read(user);
        String userId = clean(user == null ? null : user.id());
        String cid = setting.clientId() == null || setting.clientId().isEmpty() ? null : setting.clientId();
        String label = spec.label().toLowerCase(Locale.ROOT);

        if (value.isEmpty()) {
            return new JoinKeyResolution(false, setting.column(), null, JoinKeyError.MISSING,
                    "This account has no " + label + " on record, and " + (cid == null ? "this application" : cid)
                            + " identifies users by " + label + " (" + spec.source() + "). An administrator must "
                            + "set it before you can sign in here.",
                    List.of());
        }

        if (validateFormat) {
            Optional<String> problem = spec.validate(value);
            if (problem.isPresent()) {
                return new JoinKeyResolution(false, setting.column(), null, JoinKeyError.INVALID,
                        problem.get() + " " + (cid == null ? "This application" : cid) + " identifies users by "
                                + label + ", so the stored value has to be corrected before you can sign in here.",
                        List.of());
            }
        }

        if (holders != null) {
            String mine = spec.normalise(value);
            List<String> others = new ArrayList<>();
            for (JoinKeyUser h : holders) {
                String hid = clean(h == null ? null : h.id());
                if (!hid.isEmpty() && !hid.equals(userId) && spec.normalise(spec.read(h)).equals(mine)) {
                    others.add(hid);
                }
            }
            if (!others.isEmpty()) {
                return new JoinKeyResolution(false, setting.column(), null, JoinKeyError.NOT_UNIQUE,
                        (others.size() + 1) + " accounts share this " + label + ", and "
                                + (cid == null ? "this application" : cid) + " identifies users by it — so it "
                                + "cannot tell them apart. An administrator must resolve the duplicate before you "
                                + "can sign in here.",
                        others);
            }
        }

        return new JoinKeyResolution(true, setting.column(), value, null,
                spec.label() + " resolved as the 'join_key' claim for "
                        + (cid == null ? "this application" : cid) + ".",
                List.of());
    }

    /** {@link #resolve(JoinColumnSetting, JoinKeyUser, Collection, boolean)} with format validation on. */
    public static JoinKeyResolution resolve(JoinColumnSetting setting, JoinKeyUser user,
                                            Collection<JoinKeyUser> holders) {
        return resolve(setting, user, holders, true);
    }

    /**
     * Which of {@code users} (the population assigned to one client) this column would block, and
     * why — in input order. Uniqueness is answered <i>within the given population</i>.
     */
    public static List<ReadinessRow> readiness(JoinColumnSetting setting, List<JoinKeyUser> users,
                                               boolean validateFormat) {
        ColumnSpec spec = setting.spec();
        Map<String, String> seen = new HashMap<>();
        Map<String, List<String>> duplicated = new HashMap<>();

        for (JoinKeyUser user : users) {
            String value = spec.normalise(spec.read(user));
            String uid = clean(user.id());
            if (value.isEmpty() || uid.isEmpty()) {
                continue;
            }
            if (seen.containsKey(value) && !seen.get(value).equals(uid)) {
                duplicated.computeIfAbsent(value, k -> new ArrayList<>(List.of(seen.get(k)))).add(uid);
            } else {
                seen.putIfAbsent(value, uid);
            }
        }

        List<ReadinessRow> rows = new ArrayList<>();
        for (JoinKeyUser user : users) {
            String uid = clean(user.id());
            String username = user.username() == null || user.username().strip().isEmpty()
                    ? null : user.username().strip();
            String raw = spec.read(user);
            String normalised = spec.normalise(raw);

            if (raw.isEmpty()) {
                rows.add(new ReadinessRow(uid, username, JoinKeyError.MISSING, "no " + spec.source() + " on record"));
                continue;
            }
            if (validateFormat && spec.validate(raw).isPresent()) {
                rows.add(new ReadinessRow(uid, username, JoinKeyError.INVALID, "expected " + spec.shapeHint()));
                continue;
            }
            List<String> dup = duplicated.get(normalised);
            if (dup != null && dup.contains(uid)) {
                rows.add(new ReadinessRow(uid, username, JoinKeyError.NOT_UNIQUE,
                        dup.size() + " users assigned to this client share this "
                                + spec.label().toLowerCase(Locale.ROOT)));
            }
        }
        return List.copyOf(rows);
    }

    public static List<ReadinessRow> readiness(JoinColumnSetting setting, List<JoinKeyUser> users) {
        return readiness(setting, users, true);
    }

    static String clean(String s) {
        return s == null ? "" : s.strip();
    }
}
