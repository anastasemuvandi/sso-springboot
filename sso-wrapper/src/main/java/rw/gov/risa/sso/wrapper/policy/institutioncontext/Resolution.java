package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import java.util.List;

/**
 * The outcome of resolving a caller: a value, or a refusal. Either way {@code warnings}
 * lists what the reference logs at WARNING (guardrail violation, claim drift) so the
 * service layer can log it — even when the setting that would refuse is off.
 */
public sealed interface Resolution<T> {

    List<String> warnings();

    record Resolved<T>(T value, List<String> warnings) implements Resolution<T> {
        public Resolved {
            warnings = List.copyOf(warnings);
        }
    }

    record Refused<T>(Refusal refusal, List<String> warnings) implements Resolution<T> {
        public Refused {
            warnings = List.copyOf(warnings);
        }
    }

    /** The value, or throws {@link InstitutionRefusedException}. */
    default T orThrow() {
        if (this instanceof Resolved<T> r) {
            return r.value();
        }
        throw ((Refused<T>) this).refusal().toException();
    }

    default boolean isRefused() {
        return this instanceof Refused<T>;
    }
}
