package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AccessMatrixTest {

    static RosterEmployee emp(String number, String first, String last, String email, String link) {
        return new RosterEmployee(number, first, last, email, null, "active", null, link, null);
    }

    static RosterEmployee emp(String number) {
        return emp(number, "A", "B", null, null);
    }

    @Nested
    class Nid {
        @Test
        void masked_to_its_last_four() {
            assertThat(AccessMatrix.maskNid("1199080012345678")).isEqualTo("••••••••••••5678");
            assertThat(AccessMatrix.maskNid("123")).isEqualTo("•••");
            assertThat(AccessMatrix.maskNid("1234")).isEqualTo("••••");
            assertThat(AccessMatrix.maskNid("12345")).isEqualTo("•2345");
            assertThat(AccessMatrix.maskNid(" 1199080012345678 ")).isEqualTo("••••••••••••5678");
            assertThat(AccessMatrix.maskNid(null)).isNull();
            assertThat(AccessMatrix.maskNid("  ")).isNull();
        }

        @Test
        void nid_of_takes_the_first_non_blank_value() {
            MemberAccount u = new MemberAccount("u", "x", null, true, Map.of("national_id", List.of(" ", "119")));
            assertThat(AccessMatrix.nidOf(u)).isEqualTo("119");
            assertThat(AccessMatrix.nidOf(new MemberAccount("u", "x", null, true, Map.of()))).isNull();
            assertThat(AccessMatrix.nidOf(null)).isNull();
        }
    }

    @Nested
    class ItemRejection {
        @Test
        void grant_mode_cannot_take_access_away_and_revoke_mode_cannot_give_it() {
            assertThat(AccessMatrix.itemRejection(Action.GRANT, true, true)).contains(RejectionReason.STATE_CHANGED);
            assertThat(AccessMatrix.itemRejection(Action.REVOKE, false, true)).contains(RejectionReason.STATE_CHANGED);
            assertThat(AccessMatrix.itemRejection(Action.GRANT, false, true)).isEmpty();
            assertThat(AccessMatrix.itemRejection(Action.REVOKE, true, true)).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(Action.class)
        void a_read_only_column_refuses_both(Action action) {
            assertThat(AccessMatrix.itemRejection(action, true, false)).contains(RejectionReason.NOT_MANAGEABLE);
            assertThat(AccessMatrix.itemRejection(action, false, false)).contains(RejectionReason.NOT_MANAGEABLE);
        }

        @Test
        void a_locked_row_refuses_both() {
            for (AccountState account : List.of(AccountState.ELSEWHERE, AccountState.AMBIGUOUS)) {
                assertThat(AccessMatrix.itemRejection(Action.GRANT, false, true, account, null))
                        .contains(RejectionReason.ACCOUNT_UNRESOLVED);
                assertThat(AccessMatrix.itemRejection(Action.REVOKE, true, true, account, null))
                        .contains(RejectionReason.ACCOUNT_UNRESOLVED);
            }
        }

        @Test
        void read_only_beats_locked_row() {
            assertThat(AccessMatrix.itemRejection(Action.GRANT, false, false, AccountState.ELSEWHERE, null))
                    .contains(RejectionReason.NOT_MANAGEABLE);
        }

        @Test
        void a_leaver_may_lose_access_but_not_gain_it() {
            assertThat(AccessMatrix.itemRejection(Action.GRANT, false, true, AccountState.EXISTING, "terminated"))
                    .contains(RejectionReason.EMPLOYEE_LEFT);
            assertThat(AccessMatrix.itemRejection(Action.REVOKE, true, true, AccountState.EXISTING, "terminated"))
                    .isEmpty();
            // A leaver who already has access: a grant is a state change, not "left".
            assertThat(AccessMatrix.itemRejection(Action.GRANT, true, true, AccountState.EXISTING, "terminated"))
                    .contains(RejectionReason.STATE_CHANGED);
        }

        @Test
        void messages() {
            assertThat(AccessMatrix.rejectionMessage("state_changed", Action.GRANT)).startsWith("This person already has access");
            assertThat(AccessMatrix.rejectionMessage("state_changed", Action.REVOKE)).startsWith("This person no longer has access");
            assertThat(AccessMatrix.rejectionMessage("cannot_create_account", Action.REVOKE))
                    .isEqualTo("This employee has no SSO account.");
            assertThat(AccessMatrix.rejectionMessage("join_value_missing", Action.GRANT)).isEqualTo("join_value_missing");
        }

        @Test
        void codes_are_snake_case() {
            assertThat(List.of(RejectionReason.values())).extracting(RejectionReason::code).containsExactly(
                    "state_changed", "not_manageable", "duplicate", "account_unresolved", "employee_left",
                    "cannot_create_account");
            assertThat(AccountState.EXISTING.code()).isEqualTo("existing");
            assertThat(ItemStatus.APPLIED.code()).isEqualTo("applied");
            assertThat(Channel.AUTO_PROVISIONING.code()).isEqualTo("auto_provisioning");
            assertThat(Channel.AUTO_PROVISIONING.isRequestChannel()).isFalse();
            assertThat(Channel.UI.isRequestChannel()).isTrue();
        }
    }

    @Nested
    class Cells {
        @Test
        void a_denied_holder_has_no_access() {
            assertThat(AccessMatrix.hasAccess(new Holder(true, List.of(), List.of(), false))).isTrue();
            assertThat(AccessMatrix.hasAccess(new Holder(true, List.of(), List.of(), true))).isFalse();
            assertThat(AccessMatrix.hasAccess(null)).isFalse();
        }

        @Test
        void cell_shape() {
            assertThat(AccessMatrix.cell(null)).isEqualTo(new Cell(false, false, List.of()));
            assertThat(AccessMatrix.cell(new Holder(false, List.of("g"), List.of("Minagri"), true)))
                    .isEqualTo(Cell.NONE);
            assertThat(AccessMatrix.cell(new Holder(false, List.of("g"), List.of("Minagri"), false)))
                    .isEqualTo(new Cell(true, false, List.of("Minagri")));
        }

        @Test
        void column_for() {
            Column owned = AccessMatrix.columnFor("minagri-portal", "Portal", false, true);
            assertThat(owned.kind()).isEqualTo(ColumnKind.OWNED);
            assertThat(owned.type()).isEqualTo("A");
            assertThat(owned.reason()).isNull();
            Column shared = AccessMatrix.columnFor("irembo", null, true, false);
            assertThat(shared.type()).isEqualTo("C");
            assertThat(shared.editable()).isFalse();
            assertThat(shared.reason()).contains("lacks manage-clients");
        }
    }

    @Nested
    class Filters {
        @Test
        void gender_filter() {
            RosterEmployee f = new RosterEmployee("1", null, null, null, null, null, "F", null, null);
            RosterEmployee lf = new RosterEmployee("1", null, null, null, null, null, "f", null, null);
            RosterEmployee m = new RosterEmployee("1", null, null, null, null, null, "M", null, null);
            RosterEmployee none = new RosterEmployee("1", null, null, null, null, null, null, null, null);
            RosterEmployee blank = new RosterEmployee("1", null, null, null, null, null, " ", null, null);
            assertThat(AccessMatrix.genderFilter(f, "F")).isTrue();
            assertThat(AccessMatrix.genderFilter(lf, "F")).isTrue();
            assertThat(AccessMatrix.genderFilter(m, "F")).isFalse();
            assertThat(AccessMatrix.genderFilter(none, AccessMatrix.GENDER_UNKNOWN)).isTrue();
            assertThat(AccessMatrix.genderFilter(blank, AccessMatrix.GENDER_UNKNOWN)).isTrue();
            assertThat(AccessMatrix.genderFilter(m, AccessMatrix.GENDER_UNKNOWN)).isFalse();
            assertThat(AccessMatrix.genderFilter(m, null)).isTrue();
            assertThat(AccessMatrix.genderFilter(none, "X")).isTrue();
        }

        @Test
        void search_by_name_number_email_and_nid_anywhere() {
            RosterEmployee e = new RosterEmployee("E004", "Nadia", "Uwase", "nadia@a.gov.rw", "1 1990 8 0012345678",
                    "active", "F", null, null);
            assertThat(AccessMatrix.matches(e, null)).isTrue();
            assertThat(AccessMatrix.matches(e, "  ")).isTrue();
            assertThat(AccessMatrix.matches(e, "NADIA")).isTrue();
            assertThat(AccessMatrix.matches(e, "e004")).isTrue();
            assertThat(AccessMatrix.matches(e, "a.gov")).isTrue();
            assertThat(AccessMatrix.matches(e, "0012 345")).isTrue(); // spaces ignored, masked part too
            assertThat(AccessMatrix.matches(e, "zzz")).isFalse();
            assertThat(AccessMatrix.matches(e, "nadia uwase")).isTrue();
        }

        @Test
        void access_filter() {
            Map<String, Cell> cells = Map.of("a", new Cell(true, true, List.of()), "b", Cell.NONE);
            assertThat(AccessMatrix.accessFilter(cells, null, null)).isTrue();
            assertThat(AccessMatrix.accessFilter(cells, "weird", null)).isTrue();
            assertThat(AccessMatrix.accessFilter(cells, "has", null)).isTrue();
            assertThat(AccessMatrix.accessFilter(cells, "none", null)).isFalse();
            assertThat(AccessMatrix.accessFilter(cells, "has", "b")).isFalse();
            assertThat(AccessMatrix.accessFilter(cells, "none", "b")).isTrue();
            assertThat(AccessMatrix.accessFilter(cells, "none", "missing")).isTrue();
            assertThat(AccessMatrix.accessFilter(Map.of(), "none", null)).isTrue();
        }

        @Test
        void sorted_by_surname_then_first_name_then_number() {
            List<RosterEmployee> list = new ArrayList<>(List.of(
                    emp("3", "b", "Zed", null, null), emp("2", "A", "alpha", null, null),
                    emp("1", "a", "Alpha", null, null), new RosterEmployee("0", null, null, null, null, null, null, null, null)));
            list.sort(AccessMatrix.SORT_ORDER);
            assertThat(list).extracting(RosterEmployee::employeeNumber).containsExactly("0", "1", "2", "3");
        }
    }

    @Nested
    class Paging {
        @Test
        void bounds_are_clamped() {
            assertThat(AccessMatrix.pageBounds(1, 50, 0)).isEqualTo(new PageBounds(1, 50, 0));
            assertThat(AccessMatrix.pageBounds(3, 50, 120)).isEqualTo(new PageBounds(3, 50, 100));
            assertThat(AccessMatrix.pageBounds(9, 50, 120)).isEqualTo(new PageBounds(3, 50, 100));
            assertThat(AccessMatrix.pageBounds(-1, 500, 1000)).isEqualTo(new PageBounds(1, 200, 0));
            assertThat(AccessMatrix.pageBounds(null, null, 10)).isEqualTo(new PageBounds(1, 50, 0));
            assertThat(AccessMatrix.pageBounds(2, -5, 10)).isEqualTo(new PageBounds(2, 1, 1));
        }
    }

    @Nested
    class Items {
        @Test
        void dedupe_first_wins() {
            Deduped d = AccessMatrix.dedupeItems(List.of(new BulkItem("E1", "a"), new BulkItem("E1", "b"),
                    new BulkItem("E1", "a"), new BulkItem("E2", "a")));
            assertThat(d.unique()).containsExactly(new BulkItem("E1", "a"), new BulkItem("E1", "b"), new BulkItem("E2", "a"));
            assertThat(d.duplicates()).containsExactly(new BulkItem("E1", "a"));
        }

        @Test
        void summarize_counts_every_status() {
            assertThat(AccessMatrix.summarize(List.of(ItemStatus.APPLIED, ItemStatus.APPLIED, ItemStatus.FAILED)))
                    .containsEntry(ItemStatus.APPLIED, 2).containsEntry(ItemStatus.REJECTED, 0)
                    .containsEntry(ItemStatus.FAILED, 1);
        }

        @Test
        void batch_ids_are_uuid_hex() {
            assertThat(AccessMatrix.newBatchId()).matches("[0-9a-f]{32}");
            assertThat(AccessMatrix.newBatchId()).isNotEqualTo(AccessMatrix.newBatchId());
        }
    }

    @Nested
    class AccountResolution {
        final Map<String, MemberAccount> members = new LinkedHashMap<>();

        AccountResolution() {
            members.put("u1", new MemberAccount("u1", "one", "one@x", true, Map.of()));
            members.put("u2", new MemberAccount("u2", "two@x", null, true, Map.of("employee_number", List.of("E2"))));
            members.put("u3", new MemberAccount("u3", "three", "shared@x", true, Map.of()));
        }

        @Test
        void link_then_attribute_then_email() {
            Map<String, AccountMatch> out = AccessMatrix.resolveAccounts(List.of(
                    emp("E1", "a", "a", null, "u1"), emp("E2", "b", "b", null, null),
                    emp("E3", "c", "c", " SHARED@x ", null)), members);
            assertThat(out.get("E1")).isEqualTo(AccountMatch.existing("u1"));
            assertThat(out.get("E2")).isEqualTo(AccountMatch.existing("u2"));
            assertThat(out.get("E3")).isEqualTo(AccountMatch.existing("u3")); // normalised email
        }

        @Test
        void username_matches_as_email() {
            Map<String, AccountMatch> out = AccessMatrix.resolveAccounts(List.of(emp("E9", "a", "a", "TWO@x", null)), members);
            assertThat(out.get("E9")).isEqualTo(AccountMatch.existing("u2"));
        }

        @Test
        void the_attribute_beats_the_email() {
            Map<String, AccountMatch> out = AccessMatrix.resolveAccounts(List.of(emp("E2", "a", "a", "one@x", null)), members);
            assertThat(out.get("E2")).isEqualTo(AccountMatch.existing("u2"));
        }

        @Test
        void an_email_match_onto_a_linked_account_makes_both_ambiguous() {
            Map<String, AccountMatch> out = AccessMatrix.resolveAccounts(List.of(
                    emp("E1", "a", "a", null, "u1"), emp("E3", "c", "c", "one@x", null)), members);
            assertThat(out.get("E1")).isEqualTo(AccountMatch.AMBIGUOUS);
            assertThat(out.get("E3")).isEqualTo(AccountMatch.AMBIGUOUS);
        }

        @Test
        void a_link_outside_the_institution_locks_the_row() {
            assertThat(AccessMatrix.resolveAccounts(List.of(emp("E9", "a", "a", null, "someone-else")), members).get("E9"))
                    .isEqualTo(AccountMatch.ELSEWHERE);
        }

        @Test
        void an_address_two_employees_share_is_not_used_to_match() {
            Map<String, AccountMatch> out = AccessMatrix.resolveAccounts(List.of(
                    emp("E7", "a", "a", "shared@x", null), emp("E8", "b", "b", "shared@x", null)), members);
            assertThat(out.get("E7")).isEqualTo(AccountMatch.NONE);
            assertThat(out.get("E8")).isEqualTo(AccountMatch.NONE);
        }

        @Test
        void two_employees_on_one_account_are_both_ambiguous() {
            Map<String, AccountMatch> out = AccessMatrix.resolveAccounts(List.of(
                    emp("E1", "a", "a", null, "u1"), emp("E5", "b", "b", null, "u1")), members);
            assertThat(out.get("E1")).isEqualTo(AccountMatch.AMBIGUOUS);
            assertThat(out.get("E5")).isEqualTo(AccountMatch.AMBIGUOUS);
        }

        @Test
        void an_employee_number_two_members_carry_is_ambiguous() {
            Map<String, MemberAccount> m = new LinkedHashMap<>(members);
            m.put("u4", new MemberAccount("u4", "four", null, true, Map.of("employee_number", List.of(" E2 "))));
            assertThat(AccessMatrix.resolveAccounts(List.of(emp("E2")), m).get("E2")).isEqualTo(AccountMatch.AMBIGUOUS);
        }

        @Test
        void one_member_with_username_and_email_equal_is_one_hit() {
            Map<String, MemberAccount> m = Map.of("u", new MemberAccount("u", "x@y", "X@Y", true, Map.of()));
            assertThat(AccessMatrix.resolveAccounts(List.of(emp("E", "a", "a", "x@y", null)), m).get("E"))
                    .isEqualTo(AccountMatch.existing("u"));
        }

        @Test
        void no_match_is_no_account() {
            assertThat(AccessMatrix.resolveAccounts(List.of(emp("E4", "a", "a", "nobody@x", null)), members).get("E4"))
                    .isEqualTo(AccountMatch.NONE);
        }
    }
}
