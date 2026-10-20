package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code PUT …/join-column} guard: when a change needs {@code confirm} (409).
 *
 * <p>Rule: a change needs confirmation when the client has <b>live sessions</b>, or when it
 * <b>re-keys an already-configured client that has assigned users</b>. A first-time set (the
 * client recorded no column) is not gated on assigned users; re-sending the same explicit column
 * is not a change at all.
 *
 * <p>Service flow: {@link #protocolRefusal} (400) → count sessions → if {@link #isRekeying} count
 * assigned users (an unreadable population counts as 0) → {@link #evaluate} → on
 * {@code confirmationRequired} answer 409 with {@link Decision#conflictDetails()} and write
 * nothing; else write {@code after.toAttributes(...)} and reconcile the mapper
 * ({@link MapperReconciler}; failure ⇒ 502).
 */
public final class JoinColumnChangeGate {

    private JoinColumnChangeGate() {
    }

    /** True when {@code column} differs from what is in force, or nothing was explicitly recorded. */
    public static boolean isChanging(JoinColumnSetting before, JoinColumn column) {
        return before.column() != column || !before.explicit();
    }

    /** True for an already-configured client moving to a different column (assigned users matter). */
    public static boolean isRekeying(JoinColumnSetting before, JoinColumn column) {
        return before.explicit() && before.column() != column;
    }

    /** 400 message when the client is not OIDC ({@code null} protocol = openid-connect), else empty. */
    public static Optional<String> protocolRefusal(String clientId, String protocol) {
        String p = protocol == null || protocol.isEmpty() ? "openid-connect" : protocol;
        if (p.equals("openid-connect")) {
            return Optional.empty();
        }
        return Optional.of("Client '" + clientId + "' speaks '" + p + "'. The join key is an OIDC claim produced "
                + "by an OpenID Connect protocol mapper, so it cannot be configured on a SAML client.");
    }

    /**
     * The decision.
     *
     * @param before         the current setting
     * @param after          the setting to store ({@code before.withColumn(column)})
     * @param changing       see {@link #isChanging}
     * @param rekeying       see {@link #isRekeying}
     * @param activeSessions live sessions on the client
     * @param assignedUsers  assigned users counted (0 unless rekeying)
     * @param assignedIsFloor the count was truncated (report "at least N")
     * @param confirmationRequired true ⇒ 409, nothing written
     * @param message        the 409 message (null when not required)
     */
    public record Decision(JoinColumnSetting before, JoinColumnSetting after, boolean changing, boolean rekeying,
                           int activeSessions, int assignedUsers, boolean assignedIsFloor,
                           boolean confirmationRequired, String message) {

        /**
         * The 409 {@code details}: {@code message, active_sessions, assigned_users,
         * assigned_users_is_floor, current, requested{join_column}}.
         */
        public Map<String, Object> conflictDetails() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", message);
            out.put("active_sessions", activeSessions);
            out.put("assigned_users", assignedUsers);
            out.put("assigned_users_is_floor", assignedIsFloor);
            out.put("current", before.describe());
            out.put("requested", Map.of("join_column", after.column().value()));
            return out;
        }
    }

    /**
     * Decide a join-column change.
     *
     * @param assignedUsers   ignored (treated as 0) unless the change re-keys
     */
    public static Decision evaluate(JoinColumnSetting before, JoinColumn column, int activeSessions,
                                    int assignedUsers, boolean assignedIsFloor, boolean confirm) {
        boolean changing = isChanging(before, column);
        boolean rekeying = isRekeying(before, column);
        int assigned = rekeying ? Math.max(0, assignedUsers) : 0;
        boolean floor = rekeying && assignedIsFloor;
        int sessions = Math.max(0, activeSessions);
        JoinColumnSetting after = before.withColumn(column);

        boolean required = changing && (sessions > 0 || assigned > 0) && !confirm;
        String message = null;
        if (required) {
            List<String> reasons = new ArrayList<>();
            if (sessions > 0) {
                reasons.add(sessions + " active session(s), whose tokens keep carrying the old key until they expire");
            }
            if (assigned > 0) {
                reasons.add((floor ? "at least " : "") + assigned + " user(s) currently assigned, who are matched "
                        + "into the application's own records by their "
                        + before.spec().label().toLowerCase(Locale.ROOT) + " today and by their "
                        + JoinKeys.specFor(column).label().toLowerCase(Locale.ROOT) + " afterwards");
            }
            message = "Client '" + before.clientId() + "' has " + String.join(" and ", reasons) + ". "
                    + "Changing the join column from '" + before.column().value() + "' to '" + column.value()
                    + "' does not migrate any of that. Re-send with confirm=true to proceed.";
        }
        return new Decision(before, after, changing, rekeying, sessions, assigned, floor, required, message);
    }
}
