package rw.gov.risa.sso.audit.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import rw.gov.risa.sso.audit.api.dto.IntegrityReport;
import rw.gov.risa.sso.audit.chain.ChainVerifier;
import rw.gov.risa.sso.audit.chain.HashChain;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;

/**
 * {@code GET /audit/integrity/verify} (reference core/integrity.py). Runs in one REPEATABLE READ
 * snapshot so the rows, the anchor and the chain head are mutually consistent even while
 * writers keep appending, and streams rows with a cursor rather than loading the trail.
 */
@Service
public class IntegrityService {

    private final AuditEventRepository events;

    public IntegrityService(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IntegrityReport verify(OffsetDateTime from, OffsetDateTime to) {
        String anchor = from == null ? HashChain.GENESIS_HASH : events.hashBefore(from);
        ChainVerifier verifier = new ChainVerifier(anchor, events::bridges);
        events.streamChain(from, to, row -> verifier.accept(new ChainVerifier.StoredEvent(
                row.id().toString(), row.createdAt(), row.prevHash(), row.eventHash(), row.hashVersion(),
                row.hashInput())));

        Boolean tailOk = null;
        if (to == null) {
            tailOk = verifier.tailMatchesHead(events.headHash());
        }
        boolean valid = verifier.tampered() == 0 && !Boolean.FALSE.equals(tailOk);
        return new IntegrityReport(valid, verifier.total(), verifier.tampered(), verifier.firstTamperedId(),
                verifier.firstTamperedAt(), from, to, tailOk);
    }
}
