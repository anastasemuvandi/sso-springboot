package rw.gov.risa.sso.starter.roles;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller's {@code inst:<slug>:<role>} realm roles, viewed from one institution.
 *
 * <p>{@link #slug()} is, in order: the app's configured {@code gorsso.institution.slug}; the
 * token's {@code institution} claim (the user attribute sourced from IPPIS); the single
 * institution the caller holds roles in. It is {@code null} when none of those decide.
 *
 * <p>{@link #hasRole(String)} accepts a bare role ({@code "viewer"}, checked against
 * {@link #slug()}), a full realm role ({@code "inst:minagri:viewer"}) or a wildcard
 * ({@code "inst:minagri:*"} — any role in that institution).
 *
 * <p>Like every client-side view of a token, this is an authorisation input for THIS app only.
 */
public final class InstitutionRoles {

  private static final InstitutionRoles NONE = new InstitutionRoles(null, Map.of());

  private final String slug;
  private final Map<String, Set<String>> bySlug;

  private InstitutionRoles(String slug, Map<String, Set<String>> bySlug) {
    this.slug = slug;
    this.bySlug = bySlug;
  }

  public static InstitutionRoles none() {
    return NONE;
  }

  /** No roles, but a known institution (an anonymous caller of an app with a configured slug). */
  public static InstitutionRoles empty(String slug) {
    String s = normalise(slug);
    return s == null ? NONE : new InstitutionRoles(s, Map.of());
  }

  public static InstitutionRoles from(Jwt jwt, String configuredSlug) {
    Map<String, Set<String>> bySlug = new LinkedHashMap<>();
    for (String realmRole : TokenClaims.realmRoles(jwt)) {
      InstitutionRole.parse(realmRole)
          .ifPresent(r -> bySlug.computeIfAbsent(r.slug(), k -> new LinkedHashSet<>()).add(r.role()));
    }
    bySlug.replaceAll((k, v) -> Collections.unmodifiableSet(v));
    String slug = normalise(configuredSlug);
    if (slug == null) {
      slug = normalise(jwt.getClaimAsString("institution"));
    }
    if (slug == null && bySlug.size() == 1) {
      slug = bySlug.keySet().iterator().next();
    }
    return new InstitutionRoles(slug, Collections.unmodifiableMap(bySlug));
  }

  private static String normalise(String s) {
    return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
  }

  /** The institution this view is about (may be {@code null}). */
  public String slug() {
    return slug;
  }

  public Optional<String> slugIfKnown() {
    return Optional.ofNullable(slug);
  }

  /** Role names (without the {@code inst:<slug>:} prefix) held in {@link #slug()}. */
  public Set<String> roles() {
    return slug == null ? Set.of() : rolesIn(slug);
  }

  /** Role names held in {@code institution}. */
  public Set<String> rolesIn(String institution) {
    return bySlug.getOrDefault(normalise(institution), Set.of());
  }

  /** Every institution the caller holds at least one role in. */
  public Set<String> institutions() {
    return bySlug.keySet();
  }

  /** At least one {@code inst:<slug>:*} role in {@link #slug()}. */
  public boolean isMember() {
    return !roles().isEmpty();
  }

  public boolean isMemberOf(String institution) {
    return !rolesIn(institution).isEmpty();
  }

  /** See the class comment for the accepted forms. */
  public boolean hasRole(String role) {
    if (role == null || role.isBlank()) {
      return false;
    }
    if (role.startsWith("inst:")) {
      int sep = role.indexOf(':', 5);
      if (sep < 0) {
        return false;
      }
      String inst = role.substring(5, sep);
      String name = role.substring(sep + 1);
      return "*".equals(name) ? isMemberOf(inst) : rolesIn(inst).contains(name);
    }
    if ("*".equals(role)) {
      return isMember();
    }
    return roles().contains(role);
  }

  /** {@code true} when any of {@code roles} matches {@link #hasRole(String)}. */
  public boolean hasAnyRole(String... roles) {
    for (String r : roles) {
      if (hasRole(r)) {
        return true;
      }
    }
    return false;
  }

  /** Full realm-role names held in {@link #slug()} ({@code inst:<slug>:<role>}). */
  public Set<String> realmRoles() {
    Set<String> out = new LinkedHashSet<>();
    for (String r : roles()) {
      out.add("inst:" + slug + ":" + r);
    }
    return out;
  }

  @Override
  public String toString() {
    return "InstitutionRoles[slug=" + slug + ", roles=" + bySlug + "]";
  }
}
