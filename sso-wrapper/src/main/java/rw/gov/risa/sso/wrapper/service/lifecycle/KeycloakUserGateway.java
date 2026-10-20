package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.ws.rs.NotFoundException;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;

/** Opens {@link UserOps} over a fresh service-account admin client (never cached). */
@Component
public class KeycloakUserGateway {

    private final KeycloakAdminFactory factory;

    public KeycloakUserGateway(KeycloakAdminFactory factory) {
        this.factory = factory;
    }

    public UserOps open() {
        Keycloak kc = factory.newServiceAccountClient();
        return new Ops(kc, kc.realm(factory.realm()));
    }

    static final class Ops implements UserOps {
        private final Keycloak kc;
        private final RealmResource realm;

        Ops(Keycloak kc, RealmResource realm) {
            this.kc = kc;
            this.realm = realm;
        }

        @Override
        public Optional<UserRepresentation> getUser(String userId) {
            try {
                return Optional.ofNullable(realm.users().get(userId).toRepresentation());
            } catch (NotFoundException e) {
                return Optional.empty();
            }
        }

        @Override
        public List<UserRepresentation> listUsers(int first, int max) {
            return realm.users().search(null, first, max, false);
        }

        @Override
        public List<UserRepresentation> findByAttribute(String name, String value) {
            List<UserRepresentation> hits = realm.users().searchByAttributes(0, 100, null, false, name + ":" + value);
            List<UserRepresentation> exact = new ArrayList<>();
            for (UserRepresentation u : hits == null ? List.<UserRepresentation>of() : hits) {
                List<String> v = u.getAttributes() == null ? null : u.getAttributes().get(name);
                if (v != null && v.contains(value)) {
                    exact.add(u);
                }
            }
            return exact;
        }

        @Override
        public boolean updateAttributes(String userId, Map<String, String> write) {
            Optional<UserRepresentation> current = getUser(userId);
            if (current.isEmpty()) {
                return false;
            }
            UserRepresentation rep = current.get();
            String emailBefore = rep.getEmail();
            Map<String, List<String>> attrs = rep.getAttributes() == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(rep.getAttributes());
            write.forEach((k, v) -> {
                if (v == null) {
                    attrs.remove(k);
                } else {
                    attrs.put(k, List.of(v));
                }
            });
            rep.setAttributes(attrs);
            if (!Objects.equals(emailBefore, rep.getEmail())) {
                throw new IllegalStateException("refusing to change the email property from the IPPIS sync");
            }
            realm.users().get(userId).update(rep);
            return true;
        }

        @Override
        public void setEnabled(String userId, boolean enabled, Map<String, List<String>> attributeChanges) {
            UserRepresentation rep = realm.users().get(userId).toRepresentation();
            rep.setEnabled(enabled);
            if (attributeChanges != null && !attributeChanges.isEmpty()) {
                Map<String, List<String>> attrs = rep.getAttributes() == null ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(rep.getAttributes());
                attributeChanges.forEach((k, v) -> {
                    if (v == null) {
                        attrs.remove(k);
                    } else {
                        attrs.put(k, v);
                    }
                });
                rep.setAttributes(attrs);
            }
            realm.users().get(userId).update(rep);
        }

        @Override
        public List<GroupRepresentation> groups(String userId) {
            return realm.users().get(userId).groups(0, 1000, false);
        }

        @Override
        public int sessionCount(String userId) {
            return realm.users().get(userId).getUserSessions().size();
        }

        @Override
        public void logout(String userId) {
            realm.users().get(userId).logout();
        }

        @Override
        public void removeFromGroup(String userId, String groupId) {
            realm.users().get(userId).leaveGroup(groupId);
        }

        @Override
        public void addToGroup(String userId, String groupId) {
            realm.users().get(userId).joinGroup(groupId);
        }

        @Override
        public List<RoleRepresentation> realmRoles(String userId) {
            return realm.users().get(userId).roles().realmLevel().listAll();
        }

        @Override
        public void removeRealmRoles(String userId, List<RoleRepresentation> roles) {
            if (!roles.isEmpty()) {
                realm.users().get(userId).roles().realmLevel().remove(roles);
            }
        }

        @Override
        public void addRealmRoles(String userId, List<RoleRepresentation> roles) {
            if (!roles.isEmpty()) {
                realm.users().get(userId).roles().realmLevel().add(roles);
            }
        }

        @Override
        public Optional<GroupRepresentation> findGroupByName(String name) {
            List<GroupRepresentation> hits = realm.groups().groups(name, 0, 100, false);
            Deque<GroupRepresentation> queue = new ArrayDeque<>(hits == null ? List.of() : hits);
            while (!queue.isEmpty()) {
                GroupRepresentation g = queue.poll();
                if (name.equals(g.getName())) {
                    return Optional.of(g);
                }
                if (g.getSubGroups() != null) {
                    queue.addAll(g.getSubGroups());
                }
            }
            return Optional.empty();
        }

        @Override
        public void close() {
            kc.close();
        }
    }
}
