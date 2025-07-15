package sn.sensoft.identity.security;

import io.micronaut.context.annotation.Primary;
import io.micronaut.security.token.RolesFinder;
import jakarta.inject.Singleton;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Primary  // Force Micronaut à utiliser cette implémentation
public class KeycloakRolesParser implements RolesFinder {

    private static final Logger log = LoggerFactory.getLogger(KeycloakRolesParser.class);
    @Override
    public List<String> resolveRoles(Map<String, Object> claims) {
        log.info("KeycloakRolesParser - Extraction des rôles");

        // Extraire les rôles depuis realm_access.roles
        Object realmAccess = claims.get("realm_access");
        log.info("realm_access: {}", realmAccess);

        if (realmAccess instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> realmAccessMap = (Map<String, Object>) realmAccess;
            Object roles = realmAccessMap.get("roles");
            log.info("roles trouvés: {}", roles);

            if (roles instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<String> rolesList = (Collection<String>) roles;
                log.info("Rôles extraits: {}", rolesList);
                return List.copyOf(rolesList);
            }
        }

        log.info("Aucun rôle trouvé dans realm_access");
        return List.of(); // Retourner une liste vide si aucun rôle trouvé
    }
}