package rw.gov.risa.sso.wrapper.audit;

/**
 * Writes audit events. Never throws and never blocks a login on the audit service, but never
 * drops an event either: undeliverable events go to a Redis outbox that is retried.
 */
public interface AuditEmitter {

    void emit(AuditEvent event);

    default void emit(AuditEvent.Builder event) {
        emit(event.build());
    }
}
