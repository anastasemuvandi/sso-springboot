package rw.gov.risa.sso.wrapper.identity;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything the services need to read and write the mirror. A bean ONLY when the mirror is
 * configured; services take {@code ObjectProvider<IdentityStore>} and answer 503
 * {@code identity_db_not_configured} (or skip the mirror) when it is absent.
 */
public record IdentityStore(
        NamedParameterJdbcTemplate jdbc,
        TransactionTemplate tx,
        TransactionTemplate readOnlyTx,
        EmployeeRepository employees,
        EmploymentRepository employments,
        JobRepository jobs,
        EmployeeSsoLinkRepository links,
        SectorRepository sectors,
        InstitutionRepository institutions) {
}
