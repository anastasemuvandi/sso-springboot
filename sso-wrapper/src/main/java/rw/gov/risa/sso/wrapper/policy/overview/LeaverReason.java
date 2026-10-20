package rw.gov.risa.sso.wrapper.policy.overview;

/** Why a leaver's account is still a risk. */
public enum LeaverReason {
    ACCOUNT_ENABLED("account_enabled", "Account is still enabled"),
    HOLDS_APPLICATIONS("holds_applications", "Still reaches applications"),
    STILL_IN_INSTITUTION("still_in_institution", "Still a member of the institution");

    private final String code;
    private final String text;

    LeaverReason(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public String code() {
        return code;
    }

    public String text() {
        return text;
    }
}
