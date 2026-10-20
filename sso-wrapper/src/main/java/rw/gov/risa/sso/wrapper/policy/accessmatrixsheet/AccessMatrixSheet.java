package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Action;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RejectionReason;

/**
 * The access matrix as a spreadsheet — the rules. Port of {@code app/core/access_matrix_sheet.py};
 * writing/reading the .xlsx is {@link AccessMatrixWorkbook}, the import preview is
 * {@link ImportPreview}.
 *
 * <p>GRANT ONLY: clearing a {@code YES} does not revoke; it is reported ({@code removal_ignored}).
 * The hidden {@code _meta} sheet carries institution, applications and export id with an HMAC over
 * them (key passed in); the signature covers the frame, not the cells. Matching is by employee
 * number only.
 */
public final class AccessMatrixSheet {

    public static final String FORMAT = "gorsso-access-matrix";
    public static final int VERSION = 1;

    public static final String SHEET_ACCESS = "Access";
    public static final String SHEET_HELP = "Instructions";
    public static final String SHEET_META = "_meta";

    public static final String MARK = "YES";
    /** What a person may reasonably type for "give them this". */
    public static final Set<String> TRUTHY = Set.of("YES", "Y", "X", "TRUE", "1", "✓", "✔");
    /** ...and for "no". Anything else is reported rather than guessed at. */
    public static final Set<String> FALSY = Set.of("", "NO", "N", "FALSE", "0", "-");

    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final long MAX_UNCOMPRESSED_BYTES = 60L * 1024 * 1024;
    public static final int MAX_ROWS = 20_000;
    public static final Duration EXPORT_MAX_AGE = Duration.ofDays(30);

    public static final String H_EMPLOYEE = "Employee number";
    public static final List<String> IDENTITY_HEADERS = List.of(
            H_EMPLOYEE, "First name", "Last name", "Email", "IPPIS status", "SSO account", "National ID on file");

    // Outcomes of one (employee, application) mark.
    public static final String OUTCOME_GRANT = "will_grant";
    public static final String OUTCOME_CREATE = "will_create_account";
    public static final String OUTCOME_ALREADY = "already_has_access";
    public static final String OUTCOME_REMOVAL_IGNORED = "removal_ignored";
    public static final String OUTCOME_REJECTED = "rejected";
    public static final String OUTCOME_INVALID = "invalid";
    /** The outcomes the apply step carries out. */
    public static final List<String> APPLICABLE = List.of(OUTCOME_GRANT, OUTCOME_CREATE);

    public static final String REASON_NOT_IN_ROSTER = "not_in_roster";
    public static final String REASON_INVALID_VALUE = "invalid_value";
    public static final String REASON_DUPLICATE_ROW = "duplicate_row";
    public static final String REASON_UNKNOWN_COLUMN = "unknown_column";
    public static final String REASON_MISSING_COLUMN = "missing_column";
    public static final String REASON_NO_EMPLOYEE_NUMBER = "no_employee_number";

    public static final Map<String, String> PROBLEM_MESSAGES = Map.of(
            REASON_NOT_IN_ROSTER,
            "This employee number is not on the institution's employee list (any "
                    + "more). Rows are matched by employee number only.",
            REASON_INVALID_VALUE, "Not a recognised mark. Use YES to grant, or leave the cell empty.",
            REASON_DUPLICATE_ROW, "This employee appears more than once; only the first row was read.",
            REASON_UNKNOWN_COLUMN, "A column that was not in the export. It was ignored.",
            REASON_MISSING_COLUMN, "This application's column was removed from the file, so nothing was read for it.",
            REASON_NO_EMPLOYEE_NUMBER, "A row with marks but no employee number. It was ignored.");

    public static final Map<String, String> OUTCOME_MESSAGES = Map.of(
            OUTCOME_GRANT, "Will be given access.",
            OUTCOME_CREATE, "Has no SSO account yet: one will be created, then given access.",
            OUTCOME_ALREADY, "Already has access. Nothing to do.",
            OUTCOME_REMOVAL_IGNORED,
            "Has access, but the cell was cleared. Imports only grant: to take access "
                    + "away, revoke it in the access matrix.");

    public static final Map<AccountState, String> ACCOUNT_LABELS = Map.of(
            AccountState.EXISTING, "yes",
            AccountState.NONE, "no (created when granted)",
            AccountState.ELSEWHERE, "locked: account belongs to another institution",
            AccountState.AMBIGUOUS, "locked: shared with another employee");

    private AccessMatrixSheet() {
    }

    // ---- signing ----------------------------------------------------------------

    /** HMAC-SHA256 (hex) over {@link SheetMeta#canonicalPayload()}, keyed on {@code secret}. */
    public static String sign(SheetMeta meta, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = secret.getBytes(StandardCharsets.UTF_8);
            // An empty key is legal for HMAC but not for SecretKeySpec; HMAC pads keys with zeros, so
            // a single zero byte is the same key.
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(meta.canonicalPayload().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time compare of the expected signature with {@code signature} (null = mismatch). */
    public static boolean verify(SheetMeta meta, String signature, String secret) {
        byte[] expected = sign(meta, secret).getBytes(StandardCharsets.UTF_8);
        byte[] given = (signature == null ? "" : signature).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given);
    }

    /**
     * Older than {@link #EXPORT_MAX_AGE} at {@code now}. An unparseable timestamp is expired; a naive
     * one is UTC.
     */
    public static boolean isExpired(SheetMeta meta, Instant now) {
        Optional<Instant> at = parseIso(meta.exportedAt());
        if (at.isEmpty()) {
            return true;
        }
        return Duration.between(at.get(), now).compareTo(EXPORT_MAX_AGE) > 0;
    }

    static Optional<Instant> parseIso(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Optional.of(LocalDateTime.parse(value).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Optional.of(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    /** {@code access-matrix-<institution, safe chars only>-<yyyy-mm-dd>.xlsx}. */
    public static String filenameFor(String institution, String exportedAt) {
        String day = exportedAt != null && exportedAt.length() >= 10 ? exportedAt.substring(0, 10) : "export";
        StringBuilder safe = new StringBuilder();
        institution.codePoints()
                .filter(cp -> Character.isLetterOrDigit(cp) || cp == '-' || cp == '_')
                .forEach(safe::appendCodePoint);
        String s = safe.isEmpty() ? "institution" : safe.toString();
        return "access-matrix-" + s + "-" + day + ".xlsx";
    }

    public static String fileSha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the rule for one mark ------------------------------------------------------

    /**
     * What importing one cell does, or empty for nothing. Grant only: an unmarked cell is never a
     * revoke — reported when the person has access today, nothing when they have none. A marked cell
     * of someone with access is a no-op; otherwise the matrix's own grant rule decides.
     */
    public static Optional<Verdict> classify(
            boolean marked, boolean hasAccess, boolean editable, AccountState account, String status,
            boolean mayCreate) {
        if (!marked) {
            return hasAccess ? Optional.of(new Verdict(OUTCOME_REMOVAL_IGNORED, null)) : Optional.empty();
        }
        if (hasAccess) {
            return Optional.of(new Verdict(OUTCOME_ALREADY, null));
        }
        Optional<RejectionReason> reason = AccessMatrix.itemRejection(Action.GRANT, false, editable, account, status);
        if (reason.isPresent()) {
            return Optional.of(new Verdict(OUTCOME_REJECTED, reason.get().code()));
        }
        if (account == AccountState.NONE) {
            if (!mayCreate) {
                return Optional.of(new Verdict(OUTCOME_REJECTED, RejectionReason.CANNOT_CREATE_ACCOUNT.code()));
            }
            return Optional.of(new Verdict(OUTCOME_CREATE, null));
        }
        return Optional.of(new Verdict(OUTCOME_GRANT, null));
    }

    /** The message for an outcome/reason (problem message, else the matrix's grant message). */
    public static String outcomeMessage(String outcome, String reason) {
        if (reason != null && !reason.isEmpty()) {
            String m = PROBLEM_MESSAGES.get(reason);
            return m != null ? m : AccessMatrix.rejectionMessage(reason, Action.GRANT);
        }
        return OUTCOME_MESSAGES.get(outcome);
    }

    /**
     * A cell's value as a mark: TRUE, FALSE, or null when it is not one. Accepts the cell value types
     * {@code null}, {@link Boolean}, {@link Number} and {@link String}.
     */
    public static Boolean readMark(Object value) {
        if (value == null) {
            return Boolean.FALSE;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = pyStr(value).strip().toUpperCase(Locale.ROOT);
        if (TRUTHY.contains(text)) {
            return Boolean.TRUE;
        }
        if (FALSY.contains(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * A cell's value as an identifier. Excel turns a typed {@code 12345} into a number, and
     * {@code 12345.0} must still find employee 12345.
     */
    public static String text(Object value) {
        if (value == null) {
            return "";
        }
        return pyStr(value).strip();
    }

    /** {@code str(value)} as Python would print the cell value openpyxl returns (integral floats as ints). */
    static String pyStr(Object value) {
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (value instanceof Double d && !d.isInfinite() && !d.isNaN() && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        if (value instanceof Float f && f == Math.rint(f)) {
            return Long.toString(f.longValue());
        }
        return String.valueOf(value);
    }

    /** Python {@code repr(str)} for the "(found: ...)" suffix. */
    static String pyRepr(String s) {
        char q = s.contains("'") && !s.contains("\"") ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (char c : s.toCharArray()) {
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == q) {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.append(q).toString();
    }
}
