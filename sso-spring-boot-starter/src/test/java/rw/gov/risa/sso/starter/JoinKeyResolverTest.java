package rw.gov.risa.sso.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.starter.joinkey.JoinKeyColumn;
import rw.gov.risa.sso.starter.joinkey.JoinKeyResolver;
import rw.gov.risa.sso.starter.joinkey.JoinKeyValue;

class JoinKeyResolverTest {

  @Test
  void columnParsingFollowsSpecSemantics() {
    assertThat(JoinKeyColumn.parse("nid")).isEqualTo(JoinKeyColumn.NID);
    assertThat(JoinKeyColumn.parse("PHONE")).isEqualTo(JoinKeyColumn.PHONE);
    assertThat(JoinKeyColumn.parse(null)).isEqualTo(JoinKeyColumn.EMAIL); // unset = email
    assertThat(JoinKeyColumn.parse("")).isEqualTo(JoinKeyColumn.EMAIL);
    assertThatThrownBy(() -> JoinKeyColumn.parse("username")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emailIsCaseInsensitiveOthersTrimmed() {
    var email = new JoinKeyResolver("join_key", JoinKeyColumn.EMAIL);
    assertThat(email.resolve(RoleMappingTest.jwt(Map.of("join_key", " J.Doe@Gov.RW ")))).contains("j.doe@gov.rw");
    var nid = new JoinKeyResolver("join_key", JoinKeyColumn.NID);
    assertThat(nid.resolveValue(RoleMappingTest.jwt(Map.of("join_key", " 1199080012345678 "))))
        .contains(new JoinKeyValue(JoinKeyColumn.NID, "1199080012345678"));
  }

  @Test
  void missingOrBlankIsEmpty() {
    var r = new JoinKeyResolver("join_key", JoinKeyColumn.PHONE);
    assertThat(r.resolve(RoleMappingTest.jwt(Map.of()))).isEmpty();
    assertThat(r.resolve(RoleMappingTest.jwt(Map.of("join_key", "  ")))).isEmpty();
    assertThat(r.resolve((org.springframework.security.oauth2.jwt.Jwt) null)).isEmpty();
  }

  @Test
  void valueIsNotPrinted() {
    assertThat(new JoinKeyValue(JoinKeyColumn.NID, "1199080012345678").toString()).doesNotContain("1199");
  }
}
