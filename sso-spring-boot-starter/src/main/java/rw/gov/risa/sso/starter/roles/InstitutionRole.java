package rw.gov.risa.sso.starter.roles;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A parsed {@code inst:<slug>:<role>} realm role. */
public record InstitutionRole(String slug, String role) {

  /** Slug: lowercase {@code [a-z0-9-]} (uppercase accepted and normalised); role: anything after. */
  private static final Pattern PATTERN = Pattern.compile("^inst:([A-Za-z0-9-]+):(.+)$");

  public static Optional<InstitutionRole> parse(String realmRole) {
    if (realmRole == null) {
      return Optional.empty();
    }
    Matcher m = PATTERN.matcher(realmRole);
    if (!m.matches()) {
      return Optional.empty();
    }
    return Optional.of(new InstitutionRole(m.group(1).toLowerCase(Locale.ROOT), m.group(2)));
  }

  /** {@code inst:<slug>:<role>}. */
  public String realmRole() {
    return "inst:" + slug + ":" + role;
  }
}
