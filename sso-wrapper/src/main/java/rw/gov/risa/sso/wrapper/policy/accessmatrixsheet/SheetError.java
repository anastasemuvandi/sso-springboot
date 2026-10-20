package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

/**
 * The file cannot be read as an access-matrix export (HTTP 400). {@link #getMessage()} is for
 * people; {@link #code()} is a machine code.
 */
public final class SheetError extends RuntimeException {

    public static final String EMPTY_FILE = "empty_file";
    public static final String TOO_LARGE = "file_too_large";
    public static final String NOT_XLSX = "not_xlsx";
    public static final String MACROS = "macros_not_accepted";
    public static final String TOO_MUCH_DATA = "uncompressed_too_large";
    public static final String UNREADABLE = "unreadable_workbook";
    public static final String NOT_EXPORTED_HERE = "not_exported_here";
    public static final String WRONG_VERSION = "wrong_version";
    public static final String ACCESS_SHEET_MISSING = "access_sheet_missing";
    public static final String ACCESS_SHEET_EMPTY = "access_sheet_empty";
    public static final String EMPLOYEE_COLUMN_MISSING = "employee_column_missing";
    public static final String TOO_MANY_ROWS = "too_many_rows";

    private final String code;

    public SheetError(String code, String message) {
        super(message);
        this.code = code;
    }

    public SheetError(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
