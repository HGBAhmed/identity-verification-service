package sn.sensoft.identity.security;

import io.micronaut.context.annotation.Primary;
import io.micronaut.security.token.RolesFinder;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Singleton
@Primary  // Force Micronaut à utiliser cette implémentation
public class KeycloakRolesParser implements RolesFinder {

    @Override
    public List<String> resolveRoles(Map<String, Object> claims) {
        System.out.println(" KeycloakRolesParser - Extraction des rôles");

        // Extraire les rôles depuis realm_access.roles (structure Keycloak)
        Object realmAccess = claims.get("realm_access");
        System.out.println("realm_access: " + realmAccess);

        if (realmAccess instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> realmAccessMap = (Map<String, Object>) realmAccess;
            Object roles = realmAccessMap.get("roles");
            System.out.println("roles trouvés: " + roles);

            if (roles instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<String> rolesList = (Collection<String>) roles;
                System.out.println("Rôles extraits: " + rolesList);
                return List.copyOf(rolesList);
            }
        }

        System.out.println("Aucun rôle trouvé dans realm_access");
        return List.of(); // Retourner une liste vide si aucun rôle trouvé
    }
}