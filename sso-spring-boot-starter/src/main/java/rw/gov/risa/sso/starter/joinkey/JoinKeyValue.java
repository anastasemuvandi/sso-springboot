package rw.gov.risa.sso.starter.joinkey;

/** The caller's join key and what kind of value it is. */
public record JoinKeyValue(JoinKeyColumn column, String value) {
  @Override
  public String toString() {
    // Never print the value: NIDs and phone numbers are personal data.
    return "JoinKeyValue[column=" + column.wireValue() + "]";
  }
}
