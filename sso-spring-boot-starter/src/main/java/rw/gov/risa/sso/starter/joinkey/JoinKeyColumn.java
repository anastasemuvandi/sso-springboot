package rw.gov.risa.sso.starter.joinkey;

import java.util.Locale;

/**
 * What a client's {@code join_key} value is (spec §5.6, {@code sso.client.join_column}):
 * the national ID, the phone number (both user attributes) or the e-mail (a user property).
 * A client with no column set behaves as {@link #EMAIL}.
 */
public enum JoinKeyColumn {
  NID,
  PHONE,
  EMAIL;

  public static JoinKeyColumn parse(String value) {
    if (value == null || value.isBlank()) {
      return EMAIL;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "nid" -> NID;
      case "phone" -> PHONE;
      case "email" -> EMAIL;
      default ->
          throw new IllegalArgumentException(
              "gorsso.join-key.column must be nid | phone | email, got: " + value);
    };
  }

  /** The wire value ({@code nid|phone|email}). */
  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Canonical form used for lookups: e-mail is case-insensitive, the others are trimmed only. */
  public String normalise(String raw) {
    if (raw == null) {
      return null;
    }
    String v = raw.trim();
    return this == EMAIL ? v.toLowerCase(Locale.ROOT) : v;
  }
}
