package rw.gov.risa.sso.wrapper.policy.setuplink;

/**
 * What a presented token resolves to: its record (null when unknown) and the account's one live
 * hash (null when none).
 */
public record LinkLookup(LinkRecord record, String currentHash) {

    public static final LinkLookup UNKNOWN = new LinkLookup(null, null);

    /** The account to load before judging, or null when the token resolves to nothing. */
    public String userId() {
        return record == null ? null : record.userId();
    }
}
