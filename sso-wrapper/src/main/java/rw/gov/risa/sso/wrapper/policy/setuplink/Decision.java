package rw.gov.risa.sso.wrapper.policy.setuplink;

/** An eligibility outcome; {@link #send()} only for {@code sent}. */
public record Decision(String outcome) {

    public boolean send() {
        return SetupLinkPolicy.SENT.equals(outcome);
    }
}
