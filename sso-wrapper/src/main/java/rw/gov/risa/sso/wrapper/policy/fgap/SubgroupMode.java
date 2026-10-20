package rw.gov.risa.sso.wrapper.policy.fgap;

/**
 * How {@code members.include_subgroups} is expressed (ADR-0002 §9.1).
 *
 * <ul>
 *   <li>{@link #ENUMERATE} — list the institution group and every descendant (the default;
 *       on 26.2.5 a Groups permission does NOT reach children).</li>
 *   <li>{@link #PROPAGATE} — list only the top-level group and rely on propagation.</li>
 * </ul>
 */
public enum SubgroupMode {
    ENUMERATE("enumerate"),
    PROPAGATE("propagate");

    private final String wire;

    SubgroupMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
