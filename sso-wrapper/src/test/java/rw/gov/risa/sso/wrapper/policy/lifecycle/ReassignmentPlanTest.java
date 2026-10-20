package rw.gov.risa.sso.wrapper.policy.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReassignmentPlanTest {

    @Test
    void refusals() {
        assertThat(ReassignmentPlan.refusal(false, true)).isEqualTo("user_not_found");
        assertThat(ReassignmentPlan.refusal(true, false)).isEqualTo("account_disabled");
        assertThat(ReassignmentPlan.refusal(true, true)).isNull();
        assertThat(ReassignmentPlan.refusal(true, null)).isNull(); // enabled defaults to true
    }

    @Test
    void unknownGroupNamesAreRecordedNotFatal() {
        ReassignmentPlan p = ReassignmentPlan.plan(List.of("minagri", "ghost"), List.of("mineduc", "phantom"),
                Map.of("minagri", "g1", "mineduc", "g2"));
        assertThat(p.remove()).containsExactly(Map.entry("minagri", "g1"));
        assertThat(p.add()).containsExactly(Map.entry("mineduc", "g2"));
        assertThat(p.groupsNotFound()).containsExactly("ghost", "phantom");
    }
}
