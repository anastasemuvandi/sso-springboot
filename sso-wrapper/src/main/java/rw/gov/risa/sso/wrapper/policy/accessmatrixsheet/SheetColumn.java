package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

/** An application column of the export. A non-editable column is locked and cannot be imported. */
public record SheetColumn(String clientId, String name, boolean editable) {
}
