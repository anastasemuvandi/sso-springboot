package rw.gov.risa.sso.wrapper.service.accessmatrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.service.accessmatrix.ImportPlanStore.Plan;
import rw.gov.risa.sso.wrapper.service.accessmatrix.ImportPlanStore.PlannedItem;
import rw.gov.risa.sso.wrapper.support.RedisIT;

/** The import plan in a real Redis: 30-minute TTL, the stored items round-trip, consumed exactly once. */
@Tag("it")
class ImportPlanStoreIT {

    ImportPlanStore store = new ImportPlanStore(RedisIT.template());

    @BeforeEach
    void flush() {
        RedisIT.flush();
    }

    @Test
    void thePlanLivesThirtyMinutesAndIsConsumedOnce() {
        Plan plan = new Plan("minagri", "minagri.admin", "Jane Director", "m.xlsx", "sha", "exp", "2026-10-20T08:00:00+00:00",
                "minagri.admin", List.of("minagri-a"), List.of(new PlannedItem("E1001", "minagri-a")));
        store.save("imp1", plan);

        Long ttl = RedisIT.template().getExpire(ImportPlanStore.key("imp1"), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(29L * 60, 30L * 60);
        assertThat(store.get("imp1")).contains(plan);
        assertThat(RedisIT.template().opsForValue().get("access-matrix-import:imp1")).contains("\"employee_number\"");

        assertThat(store.consume("imp1")).isTrue();
        assertThat(store.consume("imp1")).isFalse();
        assertThat(store.get("imp1")).isEmpty();
    }
}
