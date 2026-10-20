package rw.gov.risa.sso.wrapper.policy.ippisperson;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews.AMBIGUOUS_MULTIPLE_PRIMARY;
import static rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews.AMBIGUOUS_NO_EMPLOYMENT;
import static rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews.STATUS_ACTIVE;
import static rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews.STATUS_TERMINATED;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Port of tests/e2e/test_ippis_person_view.py (1–19) plus edge cases. */
class IppisPersonViewsTest {

    static final String A = "MINAGRI";
    static final String B = "MINISANTE";

    static IppisJob job(String id, String start, String end, boolean secondary, String position) {
        return new IppisJob(id, null, position, null, null, null, false, null, secondary, false,
                LocalDate.parse(start), end == null ? null : LocalDate.parse(end), null, null, null);
    }

    static IppisJob job(String id, String start) {
        return job(id, start, null, false, "Officer");
    }

    static IppisJob job() {
        return job("j1", "2022-01-01");
    }

    static IppisEmployment employment(String employer, IppisJob... jobs) {
        return IppisEmployment.of(employer, jobs);
    }

    static IppisPerson person(IppisEmployment... employments) {
        return IppisPerson.of("EMP001", employments);
    }

    @Nested
    class Primary {
        @Test
        void t1_aSingleEmploymentIsThePrimaryOne() {
            assertThat(IppisPersonViews.viewOf(person(employment(A, job()))).institutionCode()).isEqualTo(A);
        }

        @Test
        void t2_ippisOwnPrimarySecondaryFlagDecidesItEvenWhenSecondaryIsOlder() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2020-01-01")),
                    employment(B, job("j2", "2015-01-01", null, true, "x"))));
            assertThat(v.institutionCode()).isEqualTo(A);
            assertThat(v.ambiguousReason()).isNull();
        }

        @Test
        void t3_twoPrimariesBreakOnTheEarliestStart() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2021-01-01")),
                    employment(B, job("j2", "2015-01-01"))));
            assertThat(v.institutionCode()).isEqualTo(B);
        }

        @Test
        void t4_twoPrimariesStartingTheSameDayAreRefused() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2020-01-01")),
                    employment(B, job("j2", "2020-01-01"))));
            assertThat(v.institutionCode()).isNull();
            assertThat(v.ambiguousReason()).isEqualTo(AMBIGUOUS_MULTIPLE_PRIMARY);
            assertThat(v.isAmbiguous()).isTrue();
            assertThat(v.position()).isNull();
        }

        @Test
        void t5_aClosedJobDoesNotCompeteForPrimary() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2015-01-01", "2024-01-01", false, "x")),
                    employment(B, job("j2", "2024-02-01"))));
            assertThat(v.institutionCode()).isEqualTo(B);
        }

        @Test
        void t6_allSecondaryStillYieldsAnInstitution() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2021-01-01", null, true, "x")),
                    employment(B, job("j2", "2020-01-01", null, true, "x"))));
            assertThat(v.institutionCode()).isEqualTo(B);
            assertThat(v.ambiguousReason()).isNull();
        }

        @Test
        void t7_theAnswerIsStableAcrossPasses() {
            List<IppisEmployment> es = new ArrayList<>(List.of(employment(A, job("j1", "2022-01-01")),
                    employment(B, job("j2", "2022-01-01"))));
            String first = IppisPersonViews.viewOf(person(es.toArray(IppisEmployment[]::new))).ambiguousReason();
            Collections.reverse(es);
            String second = IppisPersonViews.viewOf(person(es.toArray(IppisEmployment[]::new))).ambiguousReason();
            assertThat(first).isEqualTo(second).isEqualTo(AMBIGUOUS_MULTIPLE_PRIMARY);
        }

        @Test
        void t7b_orderOfInputNeverChangesTheChosenPrimary() {
            IppisEmployment a = employment(A, job("j1", "2021-01-01"));
            IppisEmployment b = employment(B, job("j2", "2015-01-01"));
            assertThat(IppisPersonViews.viewOf(person(a, b)).institutionCode())
                    .isEqualTo(IppisPersonViews.viewOf(person(b, a)).institutionCode()).isEqualTo(B);
        }

        @Test
        void t8_everyEmployerIsReportedPrimaryFirst() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2021-01-01")),
                    employment(B, job("j2", "2015-01-01"))));
            assertThat(v.allInstitutionCodes()).containsExactly(B, A);
        }

        @Test
        void ambiguousPersonStillListsEveryEmployerOnce() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2020-01-01")),
                    employment(B, job("j2", "2020-01-01")),
                    employment(A, job("j3", "2010-01-01", "2011-01-01", false, "x"))));
            assertThat(v.allInstitutionCodes()).containsExactly(A, B);
        }

        @Test
        void earliestStartUsesTheEarliestCurrentJobOfEachEmployment() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2023-01-01"), job("j0", "2010-01-01")),
                    employment(B, job("j2", "2015-01-01"))));
            assertThat(v.institutionCode()).isEqualTo(A);
        }

        @Test
        void onePrimaryAmongManySecondariesWinsOutright() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2020-01-01", null, true, "x")),
                    employment(B, job("j2", "2020-01-01")),
                    employment("RDB", job("j3", "2020-01-01", null, true, "x"))));
            assertThat(v.institutionCode()).isEqualTo(B);
            assertThat(v.allInstitutionCodes()).containsExactly(B, A, "RDB");
        }

        @Test
        void primaryEmploymentReturnsTheEmploymentNotJustACode() {
            IppisPerson p = person(employment(A, job("j1", "2022-01-01", null, false, "Accountant")));
            PrimaryEmployment pe = IppisPersonViews.primaryEmployment(p);
            assertThat(pe.ambiguousReason()).isNull();
            assertThat(IppisPersonViews.currentPosition(pe.employment())).isEqualTo("Accountant");
            assertThat(pe.employment().jobs().get(0).startDate()).isEqualTo(LocalDate.of(2022, 1, 1));
        }

        @Test
        void employmentsWithNoJobsAtAllAreNoEmploymentNotAmbiguity() {
            IppisPerson p = person(new IppisEmployment(A, A, null, null, List.of()));
            PrimaryEmployment pe = IppisPersonViews.primaryEmployment(p);
            assertThat(pe.employment()).isNull();
            assertThat(pe.ambiguousReason()).isEqualTo(AMBIGUOUS_NO_EMPLOYMENT);
            PersonView v = IppisPersonViews.viewOf(p);
            assertThat(v.isAmbiguous()).isFalse();
            assertThat(v.institutionCode()).isNull();
            // No open job anywhere, and the record has employments: a leaver.
            assertThat(v.isLeaver()).isTrue();
            assertThat(v.allInstitutionCodes()).containsExactly(A);
        }
    }

    @Nested
    class Leaver {
        @Test
        void t9_anOpenJobAnywhereMeansStillServing() {
            PersonView v = IppisPersonViews.viewOf(person(employment(A, job())));
            assertThat(v.status()).isEqualTo(STATUS_ACTIVE);
            assertThat(v.isLeaver()).isFalse();
        }

        @Test
        void t10_everyJobClosedIsALeaver() {
            PersonView v = IppisPersonViews.viewOf(person(employment(A, job("j1", "2022-01-01", "2026-01-01", false, "x"))));
            assertThat(v.status()).isEqualTo(STATUS_TERMINATED);
            assertThat(v.isLeaver()).isTrue();
        }

        @Test
        void t11_leavingOneOfTwoPostsIsNotLeavingGovernment() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2022-01-01", "2026-01-01", false, "x")),
                    employment(B, job("j2", "2022-01-01"))));
            assertThat(v.isLeaver()).isFalse();
            assertThat(v.institutionCode()).isEqualTo(B);
        }

        @Test
        void t12_aLeaverKeepsTheInstitutionTheyLeft() {
            PersonView v = IppisPersonViews.viewOf(person(employment(A, job("j1", "2022-01-01", "2026-01-01", false, "x"))));
            assertThat(v.institutionCode()).isEqualTo(A);
        }

        @Test
        void aLeaverOfTwoKeepsTheMostRecentlyEndedEmployment() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2010-01-01", "2020-01-01", false, "x")),
                    employment(B, job("j2", "2015-01-01", "2025-06-30", false, "Clerk"))));
            assertThat(v.institutionCode()).isEqualTo(B);
            assertThat(v.position()).isEqualTo("Clerk");
            assertThat(v.allInstitutionCodes()).containsExactly(B, A);
        }

        @Test
        void sameEndDateKeepsTheFirstListed() {
            PersonView v = IppisPersonViews.viewOf(person(
                    employment(A, job("j1", "2010-01-01", "2020-01-01", false, "x")),
                    employment(B, job("j2", "2015-01-01", "2020-01-01", false, "y"))));
            assertThat(v.institutionCode()).isEqualTo(A);
        }

        @Test
        void t13_aPersonWithNoEmploymentsIsNotALeaver() {
            PersonView v = IppisPersonViews.viewOf(person());
            assertThat(v.isLeaver()).isFalse();
            assertThat(v.institutionCode()).isNull();
            assertThat(v.ambiguousReason()).isNull();
            assertThat(v.allInstitutionCodes()).isEmpty();
        }
    }

    @Nested
    class Position {
        @Test
        void t14_theCurrentJobSuppliesTheTitle() {
            assertThat(IppisPersonViews.viewOf(person(employment(A, job("j1", "2022-01-01", null, false, "Accountant"))))
                    .position()).isEqualTo("Accountant");
        }

        @Test
        void t15_theLatestCurrentJobWinsWhenThereAreTwo() {
            PersonView v = IppisPersonViews.viewOf(person(employment(A,
                    job("j1", "2020-01-01", null, false, "Officer"),
                    job("j2", "2024-01-01", null, false, "Director"))));
            assertThat(v.position()).isEqualTo("Director");
        }

        @Test
        void t16_aLeaverKeepsTheTitleTheyHeld() {
            assertThat(IppisPersonViews.viewOf(person(employment(A, job("j1", "2022-01-01", "2026-01-01", false, "Clerk"))))
                    .position()).isEqualTo("Clerk");
        }

        @Test
        void aCurrentJobOutranksALaterClosedOne() {
            assertThat(IppisPersonViews.currentPosition(employment(A,
                    job("j1", "2020-01-01", null, false, "Current"),
                    job("j2", "2024-01-01", "2024-06-01", false, "Closed")))).isEqualTo("Current");
        }

        @Test
        void sameStartBreaksOnJobId() {
            assertThat(IppisPersonViews.currentPosition(employment(A,
                    job("a", "2020-01-01", null, false, "First"),
                    job("b", "2020-01-01", null, false, "Second")))).isEqualTo("Second");
        }

        @Test
        void blankTitleIsNull() {
            assertThat(IppisPersonViews.currentPosition(employment(A, job("j", "2020-01-01", null, false, "  ")))).isNull();
            assertThat(IppisPersonViews.currentPosition(new IppisEmployment(A, null, null, null, List.of()))).isNull();
        }
    }

    @Nested
    class Fields {
        @Test
        void identityFieldsAreStrippedAndBlankBecomesNull() {
            IppisPerson p = new IppisPerson(" EMP9 ", " 1199 ", null, "  ", " a@b.rw ", " Jean ", "", null, null,
                    null, null, null, List.of(employment(A, job())));
            PersonView v = IppisPersonViews.viewOf(p);
            assertThat(v.employeeNumber()).isEqualTo("EMP9");
            assertThat(v.nationalId()).isEqualTo("1199");
            assertThat(v.phone()).isNull();
            assertThat(v.email()).isEqualTo("a@b.rw");
            assertThat(v.firstName()).isEqualTo("Jean");
            assertThat(v.lastName()).isNull();
            assertThat(v.displayName()).isEqualTo("Jean");
        }

        @Test
        void displayNameFallsBackToEmployeeNumber() {
            assertThat(IppisPersonViews.viewOf(person()).displayName()).isEqualTo("EMP001");
        }
    }

    @Nested
    class FlatAdapter {
        IppisEmployeeRecord flat(EmploymentStatus status) {
            return new IppisEmployeeRecord("EMP001", null, null, null, null, null, A, "Accountant", status, null);
        }

        @Test
        void t17_aFlatRecordMapsToTheSameView() {
            PersonView v = IppisPersonViews.viewOfEmployee(flat(EmploymentStatus.ACTIVE));
            assertThat(v.employeeNumber()).isEqualTo("EMP001");
            assertThat(v.institutionCode()).isEqualTo(A);
            assertThat(v.position()).isEqualTo("Accountant");
            assertThat(v.isLeaver()).isFalse();
            assertThat(v.allInstitutionCodes()).containsExactly(A);
        }

        @Test
        void t18_aFlatRecordCanNeverBeAmbiguous() {
            assertThat(IppisPersonViews.viewOfEmployee(flat(EmploymentStatus.ACTIVE)).isAmbiguous()).isFalse();
        }

        @ParameterizedTest
        @CsvSource({"ACTIVE,false", "SUSPENDED,false", "TERMINATED,true"})
        void t19_onlyTerminatedIsALeaverOnTheFlatContract(EmploymentStatus status, boolean leaver) {
            assertThat(IppisPersonViews.viewOfEmployee(flat(status)).isLeaver()).isEqualTo(leaver);
        }

        @Test
        void nullStatusDefaultsToActiveAndBlankInstitutionIsAbsent() {
            IppisEmployeeRecord r = new IppisEmployeeRecord("E", null, null, null, null, null, "  ", null, null, null);
            assertThat(r.status()).isEqualTo(EmploymentStatus.ACTIVE);
            PersonView v = IppisPersonViews.viewOfEmployee(r);
            assertThat(v.institutionCode()).isNull();
            assertThat(v.allInstitutionCodes()).isEmpty();
        }

        @Test
        void statusWireValues() {
            assertThat(EmploymentStatus.fromWire(" Terminated ")).contains(EmploymentStatus.TERMINATED);
            assertThat(EmploymentStatus.fromWire("gone")).isEmpty();
            assertThat(EmploymentStatus.fromWire(null)).isEmpty();
            assertThat(EmploymentStatus.SUSPENDED.wire()).isEqualTo("suspended");
        }
    }

    @Test
    void employmentHelpers() {
        IppisJob withStamp = new IppisJob("j", null, null, null, null, null, false, null, false, false,
                LocalDate.parse("2020-01-01"), null, null, null, java.time.Instant.parse("2026-01-01T00:00:00Z"));
        IppisEmployment e = employment(A, withStamp, job("k", "2019-01-01", "2019-02-01", false, "x"));
        assertThat(e.currentJobs()).containsExactly(withStamp);
        assertThat(e.sourceUpdatedAt()).contains(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(employment(A, job()).sourceUpdatedAt()).isEmpty();
        assertThat(person().employeeNumber()).isEqualTo("EMP001");
    }
}
