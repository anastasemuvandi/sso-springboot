package rw.gov.risa.sso.ippismock.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The seeded roster. Every person is invented; the 16-digit NIDs are not real.
 *
 * <p><b>Part 1 — the reference mock's roster, byte-for-byte</b> ({@code _SEED} + {@code _ROSTER} in
 * {@code ippis-mock/app/main.py}): flat records, projected into {@code /people} exactly as the
 * reference does (one employment, one job {@code <number>-j1}, synthetic start date). Every gap is
 * deliberate — the provisioning rules are about people missing exactly one identifier:
 * <ul>
 *   <li>no phone (EMP002, EMP1007), no NID (EMP1008), neither (EMP1009)</li>
 *   <li>no email (EMP1010) — cannot be provisioned: the email IS the username</li>
 *   <li>a 15-digit NID (EMP1011) — present but unusable</li>
 *   <li>one email, two people (EMP1012 / EMP1013)</li>
 *   <li>suspended (EMP003, EMP1014) — still staff; terminated (EMP404, EMP1015) — leavers</li>
 *   <li>national-form phone (EMP1006 and the EMP5xxx block)</li>
 *   <li>MOH (EMP4001) — the tenth employer, deliberately absent from the registry's ippis_codes</li>
 * </ul>
 *
 * <p><b>Part 2 — multi-employment people</b>, which the reference mock could not express (its store
 * was flat; the reference kept them in {@code scripts/seed_identity_mirror_dev.py}). EMP2008 and
 * EMP2009 are copied from that script with the same identifiers, so the two fixtures agree where
 * they overlap. EMP6001-EMP6003 are new numbers, used by nobody else.
 */
public final class SeedData {

    /** Employer code → (display name, sector code). Must agree with the mirror dev seed. */
    public static final Map<String, String[]> EMPLOYERS = Map.ofEntries(
            Map.entry("MINAGRI", new String[] {"Ministry of Agriculture and Animal Resources", "02"}),
            Map.entry("MINISANTE", new String[] {"Ministry of Health", "03"}),
            Map.entry("MINEDUC", new String[] {"Ministry of Education", "04"}),
            Map.entry("MININFRA", new String[] {"Ministry of Infrastructure", "05"}),
            Map.entry("MIFOTRA", new String[] {"Ministry of Public Service and Labour", "06"}),
            Map.entry("RDB", new String[] {"Rwanda Development Board", "07"}),
            Map.entry("RRA", new String[] {"Rwanda Revenue Authority", "08"}),
            Map.entry("MINIJUST", new String[] {"Ministry of Justice", "09"}),
            Map.entry("MINEACOM", new String[] {"Ministry of Trade and Industry", "10"}),
            Map.entry("MOH", new String[] {"Regional Health Board", "03"}));

    public static final Map<String, String> SECTORS = Map.of(
            "02", "Agriculture and Natural Resources",
            "03", "Health and Social Protection",
            "04", "Education",
            "05", "Infrastructure and Urbanisation",
            "06", "Public Service and Labour",
            "07", "Economic Development and Investment",
            "08", "Public Finance and Revenue",
            "09", "Justice and Rule of Law",
            "10", "Trade and Industry");

    private static final String DEFAULT_UPDATED = "2026-08-01T08:00:00+00:00";
    /** The mirror dev seed's single change stamp. */
    private static final String MIRROR_STAMP = "2026-09-01T08:00:00Z";

    private SeedData() {
    }

    public static List<StoredPerson> roster() {
        List<StoredPerson> out = new ArrayList<>();
        // --- the four originals (reference _SEED) -------------------------------------------------
        out.add(flat("EMP001", "Jean", "Doe", "MINEDUC", "Accountant", "1199000000000001", "+250788000001",
                "jean.doe@mineduc.gov.rw", "active", "2026-06-01T08:00:00+00:00"));
        // No phone on purpose: a phone-keyed client refuses her, NID/email-keyed admit her.
        out.add(flat("EMP002", "Aline", "Uwase", "MINISANTE", "Procurement Officer", "1199000000000002", null,
                "aline.uwase@minisante.gov.rw", "active", "2026-06-15T08:00:00+00:00"));
        out.add(flat("EMP003", "Eric", "Mugisha", "MINAGRI", "Field Inspector", "1199000000000003", "+250788000003",
                "eric.mugisha@minagri.gov.rw", "suspended", "2026-06-20T08:00:00+00:00"));
        // A terminated employee: the leaver path.
        out.add(flat("EMP404", "Former", "Staff", "MINEDUC", "Clerk", "1199000000000404", "+250788000404",
                "former.staff@mineduc.gov.rw", "terminated", "2026-07-01T08:00:00+00:00"));

        // --- MINAGRI working roster (reference _ROSTER) -------------------------------------------
        // EMP1001-1003 ARE minagri.admin / minagri.hr / minagri.staff in the realm.
        out.add(minagri("EMP1001", "Alice", "Uwimana", "Institution Administrator", "1199180012340001",
                "+250788100001", "minagri.admin@spike.local"));
        out.add(minagri("EMP1002", "Beatrice", "Mukandayisenga", "HR Officer", "1198780012340002",
                "+250788100002", "minagri.hr@spike.local"));
        out.add(minagri("EMP1003", "Callixte", "Habimana", "Agronomist", "1199280012340003",
                "+250788100003", "minagri.staff@spike.local"));
        out.add(minagri("EMP1004", "Yvonne", "Mutesi", "Livestock Officer", "1199480012340004",
                "+250788100004", "yvonne.mutesi@minagri.gov.rw"));
        out.add(minagri("EMP1005", "Beatrice", "Nyirahabimana", "Planning Officer", "1198980012340005",
                "+250788100005", "beatrice.nyirahabimana@minagri.gov.rw"));
        // National-form phone: stored exactly as IPPIS sent it.
        out.add(minagri("EMP1006", "Gilbert", "Nzeyimana", "Mechanization Technician", "1199080012340006",
                "0788100006", "gilbert.nzeyimana@minagri.gov.rw"));
        // One identifier missing: the per-client rule.
        out.add(minagri("EMP1007", "Emmanuel", "Nsengiyumva", "Irrigation Engineer", "1198680012340007",
                null, "emmanuel.nsengiyumva@minagri.gov.rw"));
        out.add(minagri("EMP1008", "Diane", "Uwimana", "Data Analyst", null,
                "+250788100008", "diane.uwimana@minagri.gov.rw"));
        out.add(minagri("EMP1009", "Claudine", "Mukamana", "Extension Officer", null,
                null, "claudine.mukamana@minagri.gov.rw"));
        // No email: nothing to name an account after.
        out.add(minagri("EMP1010", "Patrick", "Kalisa", "Storekeeper", "1198880012340010",
                "+250788100010", null));
        // A 15-digit NID: present, and not a NID.
        out.add(minagri("EMP1011", "Solange", "Iribagiza", "Soil Scientist", "119918001234011",
                "+250788100011", "solange.iribagiza@minagri.gov.rw"));
        // One address, two people.
        out.add(minagri("EMP1012", "Theoneste", "Bizimana", "Procurement Officer", "1198580012340012",
                "+250788100012", "t.bizimana@minagri.gov.rw"));
        out.add(minagri("EMP1013", "Josiane", "Bizimana", "Procurement Assistant", "1199680012340013",
                "+250788100013", "t.bizimana@minagri.gov.rw"));
        // Suspended is still staff; terminated is a leaver.
        out.add(flat("EMP1014", "Fabrice", "Ndayisaba", "MINAGRI", "Driver", "1199380012340014", "+250788100014",
                "fabrice.ndayisaba@minagri.gov.rw", "suspended", DEFAULT_UPDATED));
        out.add(flat("EMP1015", "Chantal", "Ingabire", "MINAGRI", "Accountant", "1198480012340015", "+250788100015",
                "chantal.ingabire@minagri.gov.rw", "terminated", "2026-08-20T08:00:00+00:00"));
        // A named tester on a real mailbox (MailHog runs).
        out.add(minagri("EMP2010", "Jean Paul Christian", "Mutabazi", "Officer", "1199080000000210",
                "0788200010", "jpchristian.mutabazi@nrs.gov.rw"));
        // So the platform-wide view is not one institution.
        out.add(active("EMP2101", "Samuel", "Twagirayezu", "MINISANTE", "Health Information Officer",
                "1199780012342001", "+250788200001", "samuel.twagirayezu@minisante.gov.rw"));
        out.add(active("EMP3101", "Immaculee", "Nyiramana", "MINEDUC", "School Inspector",
                "1198380012343001", "+250788300001", "immaculee.nyiramana@mineduc.gov.rw"));

        // --- the rest of the registry: ten institutions --------------------------------------------
        out.add(active("EMP5001", "Aimable", "Rwamasirabo", "MINEDUC", "Education Planning Specialist",
                "1199080000000501", "0788500001", "aimable.rwamasirabo@mineduc.gov.rw"));
        out.add(active("EMP5002", "Denise", "Mukeshimana", "MINEDUC", "School Inspector",
                "1199080000000502", "0788500002", "denise.mukeshimana@mineduc.gov.rw"));
        out.add(active("EMP5003", "Boniface", "Twizeyimana", "MININFRA", "Civil Engineer",
                "1199080000000503", "0788500003", "boniface.twizeyimana@mininfra.gov.rw"));
        out.add(active("EMP5004", "Josephine", "Nyirandegeya", "MININFRA", "Transport Planner",
                "1199080000000504", "0788500004", "josephine.nyirandegeya@mininfra.gov.rw"));
        out.add(active("EMP5005", "Egide", "Munyakazi", "MIFOTRA", "Payroll Officer",
                "1199080000000505", "0788500005", "egide.munyakazi@mifotra.gov.rw"));
        out.add(active("EMP5006", "Sandrine", "Nikuze", "MIFOTRA", "Labour Inspector",
                "1199080000000506", "0788500006", "sandrine.nikuze@mifotra.gov.rw"));
        out.add(active("EMP5007", "Fidele", "Rukundo", "RDB", "Investment Analyst",
                "1199080000000507", "0788500007", "fidele.rukundo@rdb.gov.rw"));
        out.add(active("EMP5008", "Peace", "Mukamurenzi", "RDB", "Company Registrar Officer",
                "1199080000000508", "0788500008", "peace.mukamurenzi@rdb.gov.rw"));
        out.add(active("EMP5009", "Anaclet", "Bizimungu", "RRA", "Tax Auditor",
                "1199080000000509", "0788500009", "anaclet.bizimungu@rra.gov.rw"));
        out.add(active("EMP5010", "Chantal", "Nirere", "RRA", "Customs Officer",
                "1199080000000510", "0788500010", "chantal.nirere@rra.gov.rw"));
        out.add(active("EMP5011", "Herman", "Nsabimana", "MINIJUST", "State Attorney",
                "1199080000000511", "0788500011", "herman.nsabimana@minijust.gov.rw"));
        out.add(active("EMP5012", "Liliane", "Uwase", "MINIJUST", "Legal Aid Coordinator",
                "1199080000000512", "0788500012", "liliane.uwase@minijust.gov.rw"));
        out.add(active("EMP5013", "Pascal", "Munyaneza", "MINEACOM", "Trade Policy Analyst",
                "1199080000000513", "0788500013", "pascal.munyaneza@mineacom.gov.rw"));
        out.add(active("EMP5014", "Rosine", "Mutabazi", "MINEACOM", "Industrial Development Officer",
                "1199080000000514", "0788500014", "rosine.mutabazi@mineacom.gov.rw"));
        // An employer the registry does not know (MOH): visible only to a platform admin.
        out.add(active("EMP4001", "Samuel", "Rugema", "MOH", "Regional Coordinator",
                "1199080000000401", "0788400001", "samuel.rugema@moh.example.rw"));

        // --- Part 2: multi-employment people -------------------------------------------------------
        // A leaver in the nested feed: every job closed (from the mirror dev seed).
        out.add(nested("EMP2008", "Marie", "Nyiransabimana", "marie.nyiransabimana@minagri.gov.rw",
                "0788200008", "1199080000000208",
                employment("MINAGRI", job("j2008", "2015-01-05", "2026-06-30", false, "Registry Clerk",
                        "Under statute"))));
        // A dual appointment: MINAGRI primary, MINISANTE secondary (from the mirror dev seed). Appears in
        // both institutions' lists; only MINAGRI's admin may manage the account.
        out.add(nested("EMP2009", "Olivier", "Habyarimana", "olivier.habyarimana@minagri.gov.rw",
                "0788200009", "1199080000000209",
                employment("MINAGRI", job("j2009a", "2018-04-02", null, false, "Chief Veterinary Officer",
                        "Under statute")),
                employment("MINISANTE", job("j2009b", "2022-09-01", null, true, "Board Member",
                        "Board Member"))));
        // A mover: held two posts, resigned the PRIMARY one; the secondary post is still open. Not a
        // leaver (rule 3); the only open post is secondary-only, so it becomes the institution.
        out.add(nested("EMP6001", "Didier", "Hakizimana", "didier.hakizimana@mineduc.gov.rw",
                "0788600001", "1199080000000601",
                employment("MINEDUC", job("j6001a", "2015-09-01", "2026-08-31", false, "Head of Planning",
                        "Under statute")),
                employment("MINAGRI", job("j6001b", "2021-03-01", null, true, "Technical Advisor",
                        "Contract"))));
        // Ambiguous primary: two primary posts starting the same day. Institution left unwritten.
        out.add(nested("EMP6002", "Esperance", "Mukarugwiza", "esperance.mukarugwiza@minijust.gov.rw",
                "0788600002", "1199080000000602",
                employment("MINIJUST", job("j6002a", "2022-03-01", null, false, "Legal Advisor",
                        "Under statute")),
                employment("RDB", job("j6002b", "2022-03-01", null, false, "Legal Advisor",
                        "Under statute"))));
        // Two primary posts, different start dates: the earliest-held one wins (MININFRA).
        out.add(nested("EMP6003", "Jean Bosco", "Niyonzima", "jeanbosco.niyonzima@mininfra.gov.rw",
                "0788600003", "1199080000000603",
                employment("MININFRA", job("j6003a", "2017-05-02", null, false, "Senior Engineer",
                        "Under statute")),
                employment("RRA", job("j6003b", "2023-01-09", null, false, "Infrastructure Auditor",
                        "Under statute"))));
        return out;
    }

    private static StoredPerson flat(String number, String first, String last, String institution, String position,
            String nid, String phone, String email, String status, String updated) {
        StoredPerson p = new StoredPerson();
        p.setEmployeeNumber(number);
        p.setFirstName(first);
        p.setLastName(last);
        p.setInstitution(institution);
        p.setPosition(position);
        p.setStatus(status);
        p.setUpdatedAt(updated);
        p.setNationalId(nid);
        p.setPhone(phone);
        p.setEmail(email);
        return p;
    }

    private static StoredPerson active(String number, String first, String last, String institution,
            String position, String nid, String phone, String email) {
        return flat(number, first, last, institution, position, nid, phone, email, "active", DEFAULT_UPDATED);
    }

    private static StoredPerson minagri(String number, String first, String last, String position, String nid,
            String phone, String email) {
        return active(number, first, last, "MINAGRI", position, nid, phone, email);
    }

    private static StoredPerson nested(String number, String first, String last, String email, String phone,
            String nid, Employment... employments) {
        StoredPerson p = new StoredPerson();
        p.setEmployeeNumber(number);
        p.setFirstName(first);
        p.setLastName(last);
        p.setEmail(email);
        p.setPhone(phone);
        p.setNationalId(nid);
        p.setUpdatedAt(MIRROR_STAMP);
        p.setCreatedAt("2024-01-01T10:00:00Z");
        p.setGender("aeiou".indexOf(first.charAt(first.length() - 1)) >= 0 ? "F" : "M");
        p.setMaritalStatus("S");
        p.setDateOfBirth("1990-01-01");
        p.setEmployments(new ArrayList<>(Arrays.asList(employments)));
        return p;
    }

    static Employment employment(String employer, Job... jobs) {
        String[] meta = EMPLOYERS.get(employer);
        String name = meta == null ? employer : meta[0];
        String sector = meta == null ? null : meta[1];
        return new Employment(employer, name, sector, sector == null ? null : SECTORS.get(sector), List.of(jobs));
    }

    private static Job job(String id, String start, String end, boolean secondary, String position, String group) {
        return new Job(id, null, position, null, "Directorate", "3.IV", null, group, secondary, null, start, end,
                end == null ? "New" : "Removed", null, MIRROR_STAMP);
    }
}
