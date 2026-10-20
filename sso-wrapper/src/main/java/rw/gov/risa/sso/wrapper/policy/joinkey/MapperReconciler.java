package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Make a client's {@code sso-join-key} mapper match a setting — the decision only.
 *
 * <p>Matched by <b>name</b>, never by id: a column change updates the same mapper rather than
 * adding a second {@code join_key} claim. Unchanged when type, {@code user.attribute} and
 * {@code claim.name} already match. An update merges onto the server's representation (keeps its
 * id and any config keys we do not restate). A failed write must surface as 502
 * ({@link #WRITE_FAILED_MESSAGE}).
 */
public final class MapperReconciler {

    private MapperReconciler() {
    }

    /** What the service must do. {@link #code()} is the reported string. */
    public enum Kind {
        CREATED("created"),
        UNCHANGED("unchanged"),
        UPDATED("updated");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * @param kind  the action
     * @param write the mapper to POST (CREATED, id null) or PUT (UPDATED, id of the existing one);
     *              for UNCHANGED, the existing mapper
     */
    public record Action(Kind kind, MapperSpec write) {
    }

    /** 502 detail when the mapper write fails. */
    public static String writeFailedMessage(String claim) {
        return "The join column was recorded but its protocol mapper could not be written, so tokens would not "
                + "carry the '" + claim + "' claim. Retry the change; if it keeps failing, check that the SSO "
                + "service account holds realm-management:manage-clients.";
    }

    public static final String WRITE_FAILED_MESSAGE = writeFailedMessage(JoinKeys.JOIN_KEY_CLAIM);

    /** Plan the reconcile of {@code setting} against the client's {@code existing} mappers. */
    public static Action plan(JoinColumnSetting setting, List<MapperSpec> existing) {
        MapperSpec desired = setting.spec().mapper(setting.claim());
        MapperSpec match = null;
        if (existing != null) {
            for (MapperSpec m : existing) {
                if (m != null && JoinKeys.JOIN_KEY_MAPPER_NAME.equals(m.name())) {
                    match = m;
                    break;
                }
            }
        }
        if (match == null) {
            return new Action(Kind.CREATED, desired);
        }
        if (Objects.equals(match.protocolMapper(), desired.protocolMapper())
                && Objects.equals(match.config().get("user.attribute"), desired.config().get("user.attribute"))
                && Objects.equals(match.config().get("claim.name"), desired.config().get("claim.name"))) {
            return new Action(Kind.UNCHANGED, match);
        }
        Map<String, String> config = new LinkedHashMap<>(match.config());
        config.putAll(desired.config());
        return new Action(Kind.UPDATED, new MapperSpec(match.id(), desired.name(), desired.protocol(),
                desired.protocolMapper(), config));
    }
}
