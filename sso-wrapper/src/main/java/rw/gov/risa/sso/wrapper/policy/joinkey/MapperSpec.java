package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A protocol mapper as plain data — the desired {@code sso-join-key} mapper, or one read from a
 * client. The service adapts it to/from {@code ProtocolMapperRepresentation}.
 *
 * @param id             the mapper id ({@code null} for a desired mapper not yet created)
 * @param name           mapper name ({@value JoinKeys#JOIN_KEY_MAPPER_NAME} for ours)
 * @param protocol       {@code openid-connect}
 * @param protocolMapper {@code oidc-usermodel-property-mapper} (email) or
 *                       {@code oidc-usermodel-attribute-mapper} (nid/phone)
 * @param config         mapper config (ordered)
 */
public record MapperSpec(String id, String name, String protocol, String protocolMapper, Map<String, String> config) {

    public MapperSpec {
        config = config == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }
}
