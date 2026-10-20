package rw.gov.risa.sso.wrapper.policy.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyIdsTest {

    @Test
    void userIds() {
        assertThat(PolicyIds.userIds("[\"a\",\"\",5,null,\"b\"]")).containsExactly("a", "b");
        assertThat(PolicyIds.userIds(null)).isEmpty();
        assertThat(PolicyIds.userIds("")).isEmpty();
        assertThat(PolicyIds.userIds("{not json")).isEmpty();
        assertThat(PolicyIds.userIds("{\"a\":1}")).isEmpty();
    }

    @Test
    void groupIds() {
        assertThat(PolicyIds.groupIds("[{\"id\":\"g1\",\"extendChildren\":false},{\"id\":\"\"},\"g2\",{\"path\":\"/x\"}]"))
                .containsExactly("g1");
        assertThat(PolicyIds.groupIds("nope")).isEmpty();
    }

    @Test
    void encodeRoundTrips() {
        assertThat(PolicyIds.userIds(PolicyIds.encodeUsers(List.of("a", "b")))).containsExactly("a", "b");
        assertThat(PolicyIds.encodeGroups(List.of("g"))).isEqualTo("[{\"id\":\"g\",\"extendChildren\":false}]");
        assertThat(PolicyIds.groupIds(PolicyIds.encodeGroups(List.of("g1", "g2")))).containsExactly("g1", "g2");
    }
}
