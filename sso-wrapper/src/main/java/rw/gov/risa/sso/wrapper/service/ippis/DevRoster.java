package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisEmployment;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisJob;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;

/**
 * The DEV roster ({@code scripts/seed_identity_mirror_dev.py}), chosen so every state the directory
 * and provisioning must handle is on one page: people with accounts, the joiner queue, no email, a
 * leaver, a dual appointment, an unmapped employer, an AMBIGUOUS primary (two primary posts starting
 * the same day) and two employees per remaining registry institution. Invented people; national
 * ids are not real. Shares its key space with ippis-mock (same numbers = same person).
 */
public final class DevRoster {

    static final Instant STAMP = Instant.parse("2026-09-01T08:00:00Z");

    static final Map<String, String[]> EMPLOYERS = Map.ofEntries(
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

    static final Map<String, String> SECTORS = Map.of(
            "02", "Agriculture and Natural Resources", "03", "Health and Social Protection", "04", "Education",
            "05", "Infrastructure and Urbanisation", "06", "Public Service and Labour",
            "07", "Economic Development and Investment", "08", "Public Finance and Revenue",
            "09", "Justice and Rule of Law", "10", "Trade and Industry");

    private DevRoster() {
    }

    static IppisJob job(String id, String start, String end, boolean secondary, String position, String group) {
        return new IppisJob(id, null, position, null, "Directorate", "3.IV", false, group, secondary, false,
                LocalDate.parse(start), end == null ? null : LocalDate.parse(end), end != null ? "Removed" : "New",
                null, STAMP);
    }

    static IppisJob job(String id, String start, String position) {
        return job(id, start, null, false, position, "Under statute");
    }

    static IppisEmployment employment(String employer, IppisJob... jobs) {
        String[] e = EMPLOYERS.getOrDefault(employer, new String[] {employer, null});
        return new IppisEmployment(employer, e[0], e[1], e[1] == null ? null : SECTORS.get(e[1]), List.of(jobs));
    }

    static IppisPerson person(String number, String first, String last, String email, String phone, String nid,
                              IppisEmployment... employments) {
        String gender = "aeiou".indexOf(first.toLowerCase(Locale.ROOT).charAt(first.length() - 1)) >= 0 ? "F" : "M";
        return new IppisPerson(number, nid, null, phone, email, first, last, gender, "S", LocalDate.parse("1990-01-01"),
                Instant.parse("2024-01-01T10:00:00Z"), STAMP, List.of(employments));
    }

    private static String mg(String n) {
        return n + "@minagri.gov.rw";
    }

    private static String ms(String n) {
        return n + "@minisante.gov.rw";
    }

    private static String gov(String employer, String n) {
        return n + "@" + employer.toLowerCase(Locale.ROOT) + ".gov.rw";
    }

    public static List<IppisPerson> roster() {
        List<IppisPerson> r = new ArrayList<>();
        // -- already have accounts in a seeded dev realm --------------------------------------
        r.add(person("EMP1001", "Alice", "Uwimana", "minagri.admin@spike.local", "+250788100001", "1199180012340001",
                employment("MINAGRI", job("j1001", "2019-03-01", "Institution Administrator"))));
        r.add(person("EMP1002", "Beatrice", "Mukandayisenga", "minagri.hr@spike.local", "+250788100002",
                "1198780012340002", employment("MINAGRI", job("j1002", "2020-06-15", "HR Officer"))));
        r.add(person("EMP1003", "Callixte", "Habimana", "minagri.staff@spike.local", "+250788100003",
                "1199280012340003", employment("MINAGRI", job("j1003", "2021-01-04", "Agronomist"))));
        r.add(person("EMP1004", "Yvonne", "Mutesi", mg("yvonne.mutesi"), "+250788100004", "1199480012340004",
                employment("MINAGRI", job("j1004", "2018-09-01", "Extension Officer"))));
        r.add(person("EMP1005", "Beatrice", "Nyirahabimana", mg("beatrice.nyirahabimana"), "+250788100005",
                "1198980012340005", employment("MINAGRI", job("j1005", "2022-02-01", "Veterinary Inspector"))));
        r.add(person("EMP1006", "Gilbert", "Nzeyimana", mg("gilbert.nzeyimana"), "0788100006", "1199080012340006",
                employment("MINAGRI", job("j1006", "2017-11-20", "Director of Planning"))));
        r.add(person("EMP1007", "Emmanuel", "Nsengiyumva", mg("emmanuel.nsengiyumva"), null, "1198680012340007",
                employment("MINAGRI", job("j1007", "2023-04-03", "Data Analyst"))));
        r.add(person("EMP1008", "Diane", "Uwimana", mg("diane.uwimana"), "+250788100008", null,
                employment("MINAGRI", job("j1008", "2020-08-17", "Irrigation Engineer"))));
        r.add(person("EMP1009", "Claudine", "Mukamana", mg("claudine.mukamana"), null, null,
                employment("MINAGRI", job("j1009", "2021-05-10", "Accountant"))));
        r.add(person("EMP1012", "Theoneste", "Bizimana", mg("t.bizimana"), "+250788100012", "1198580012340012",
                employment("MINAGRI", job("j1012", "2016-02-01", "Soil Scientist"))));
        r.add(person("EMP1014", "Fabrice", "Ndayisaba", mg("fabrice.ndayisaba"), "+250788100014", "1199380012340014",
                employment("MINAGRI", job("j1014", "2024-01-08", "ICT Officer"))));
        r.add(person("EMP003", "Eric", "Mugisha", mg("eric.mugisha"), "+250788000003", "1199000000000003",
                employment("MINAGRI", job("j003", "2019-07-01", "Procurement Officer"))));
        // -- the joiner queue: IPPIS knows them, the SSO does not -------------------------------
        r.add(person("EMP2001", "Aline", "Kamana", mg("aline.kamana"), "0788200001", "1199080000000201",
                employment("MINAGRI", job("j2001", "2025-01-06", "Agricultural Economist"))));
        r.add(person("EMP2002", "Jean", "Ndagijimana", mg("jean.ndagijimana"), "0788200002", "1199080000000202",
                employment("MINAGRI", job("j2002", "2025-02-17", "Field Coordinator"))));
        r.add(person("EMP2003", "Solange", "Ingabire", mg("solange.ingabire"), "0788200003", "1199080000000203",
                employment("MINAGRI", job("j2003", "2025-03-03", "Laboratory Technician"))));
        r.add(person("EMP2004", "Patrick", "Rwigema", mg("patrick.rwigema"), "0788200004", "1199080000000204",
                employment("MINAGRI", job("j2004", "2025-06-02", null, false, "Intern", "Intern"))));
        r.add(person("EMP2005", "Josiane", "Umutoni", mg("josiane.umutoni"), "0788200005", "1199080000000205",
                employment("MINAGRI", job("j2005", "2025-07-14", "Communications Officer"))));
        r.add(person("EMP2010", "Jean Paul Christian", "Mutabazi", "jpchristian.mutabazi@nrs.gov.rw", "0788200010",
                "1199080000000210", employment("MINAGRI", job("j2010", "2025-09-01", "Officer"))));
        // -- no email: no account can be created -------------------------------------------------
        r.add(person("EMP2007", "Vincent", "Karangwa", null, "0788200007", "1199080000000207",
                employment("MINAGRI", job("j2007", "2024-10-01", null, false, "Driver", "Casual Worker"))));
        // -- a leaver: every job closed, no account (seeding disables nobody) ----------------------
        r.add(person("EMP2008", "Marie", "Nyiransabimana", mg("marie.nyiransabimana"), "0788200008",
                "1199080000000208",
                employment("MINAGRI", job("j2008", "2015-01-05", "2026-06-30", false, "Registry Clerk",
                        "Under statute"))));
        // -- a dual appointment: MINAGRI primary, MINISANTE secondary -----------------------------
        r.add(person("EMP2009", "Olivier", "Habyarimana", mg("olivier.habyarimana"), "0788200009", "1199080000000209",
                employment("MINAGRI", job("j2009a", "2018-04-02", "Chief Veterinary Officer")),
                employment("MINISANTE", job("j2009b", "2022-09-01", null, true, "Board Member", "Board Member"))));
        // -- an AMBIGUOUS primary: two primary posts starting the same day (institution unwritten) -
        r.add(person("EMP2011", "Clarisse", "Uwamahoro", mg("clarisse.uwamahoro"), "0788200011", "1199080000000211",
                employment("MINAGRI", job("j2011a", "2024-03-01", "Programme Officer")),
                employment("MINEDUC", job("j2011b", "2024-03-01", "Programme Officer"))));
        // -- minisante, so a scoped read visibly excludes them --------------------------------------
        r.add(person("EMP3001", "Chantal", "Uwera", ms("chantal.uwera"), "0788300001", "1199080000000301",
                employment("MINISANTE", job("j3001", "2020-01-06", "Clinical Officer"))));
        r.add(person("EMP3002", "Innocent", "Twagirayezu", ms("innocent.twagirayezu"), "0788300002",
                "1199080000000302", employment("MINISANTE", job("j3002", "2019-05-20", "Health Records Manager"))));
        r.add(person("EMP3003", "Grace", "Ineza", ms("grace.ineza"), "0788300003", "1199080000000303",
                employment("MINISANTE", job("j3003", "2023-08-01", "Pharmacist"))));
        // -- an employer the registry does not know: belongs to no institution ---------------------
        r.add(person("EMP4001", "Samuel", "Rugema", "samuel.rugema@moh.example.rw", "0788400001", "1199080000000401",
                employment("MOH", job("j4001", "2021-02-01", "Regional Coordinator"))));
        // -- the rest of the registry, two people each (all joiner-queue) ---------------------------
        String[][] rest = {
                {"EMP5001", "Aimable", "Rwamasirabo", "MINEDUC", "j5001", "2019-09-02", "Education Planning Specialist"},
                {"EMP5002", "Denise", "Mukeshimana", "MINEDUC", "j5002", "2022-01-10", "School Inspector"},
                {"EMP5003", "Boniface", "Twizeyimana", "MININFRA", "j5003", "2018-06-01", "Civil Engineer"},
                {"EMP5004", "Josephine", "Nyirandegeya", "MININFRA", "j5004", "2023-03-15", "Transport Planner"},
                {"EMP5005", "Egide", "Munyakazi", "MIFOTRA", "j5005", "2020-02-03", "Payroll Officer"},
                {"EMP5006", "Sandrine", "Nikuze", "MIFOTRA", "j5006", "2021-11-08", "Labour Inspector"},
                {"EMP5007", "Fidele", "Rukundo", "RDB", "j5007", "2017-04-17", "Investment Analyst"},
                {"EMP5008", "Peace", "Mukamurenzi", "RDB", "j5008", "2024-05-06", "Company Registrar Officer"},
                {"EMP5009", "Anaclet", "Bizimungu", "RRA", "j5009", "2016-08-01", "Tax Auditor"},
                {"EMP5010", "Chantal", "Nirere", "RRA", "j5010", "2022-07-25", "Customs Officer"},
                {"EMP5011", "Herman", "Nsabimana", "MINIJUST", "j5011", "2019-01-14", "State Attorney"},
                {"EMP5012", "Liliane", "Uwase", "MINIJUST", "j5012", "2023-10-02", "Legal Aid Coordinator"},
                {"EMP5013", "Pascal", "Munyaneza", "MINEACOM", "j5013", "2018-11-05", "Trade Policy Analyst"},
                {"EMP5014", "Rosine", "Mutabazi", "MINEACOM", "j5014", "2025-04-21", "Industrial Development Officer"}};
        for (String[] x : rest) {
            String n = x[0].substring(3);
            r.add(person(x[0], x[1], x[2], gov(x[3], x[1].toLowerCase(Locale.ROOT) + "." + x[2].toLowerCase(Locale.ROOT)),
                    "0788" + n.charAt(0) + "00" + n.substring(1), "119908000000" + "0" + n.charAt(0) + n.substring(2),
                    employment(x[3], job(x[4], x[5], x[6]))));
        }
        return r;
    }
}
