package org.cardanofoundation.cip113.config;

import java.net.URL;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.keri.IdentifierConfig;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.generated.keria.model.HabState;
import org.cardanofoundation.signify.generated.keria.model.Tier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@EnableConfigurationProperties(SchemaConfig.class)
@Slf4j
public class KeriConfig {

    public record WitnessInfo(String eid, String oobi) {}
    public record AvailableWitnesses(int toad, List<WitnessInfo> witnesses) {}

    @Bean
    public SignifyClient signifyClient(@Value("${keri.url}") String url,
                                       @Value("${keri.identifier.bran}") String bran,
                                       @Value("${keri.booturl}") String bootUrl,
                                       SchemaConfig schemaConfig) throws Exception {
        SignifyClient client = new SignifyClient(url, bran, Tier.LOW, bootUrl, null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }

        if (schemaConfig.getSchemas() != null) {
            for (SchemaConfig.SchemaEntry entry : schemaConfig.getSchemas().values()) {
                Object resolve = client.oobis().resolve(schemaConfig.getBaseUrl() + entry.getSaid(), null);
                client.operations().wait(Operation.fromObject(resolve));
            }
        }

        return client;
    }

    @Bean
    public IdentifierConfig createIdentifier(
            @Value("${keri.identifier.name}") String identifierName,
            @Value("${keri.identifier.role}") String role,
            SignifyClient client) throws Exception {
        String prefix;

        Optional<HabState> habState = client.identifiers().get(identifierName);
        if (habState.isPresent()) {
            prefix = habState.get().getPrefix();
        } else {
            log.info("KERI Identifier with name {} not found, creating new one", identifierName);
            prefix = createAid(client, identifierName);
        }
        log.info("Using KERI Identifier with name {} and prefix {}", identifierName, prefix);
        return IdentifierConfig.builder()
                .prefix(prefix)
                .name(identifierName)
                .role(role)
                .build();
    }

    @SuppressWarnings("unchecked")
    public static String createAid(SignifyClient client, String name) throws Exception {
        Object id = null;
        String eid = "";

        AvailableWitnesses availableWitnesses = getAvailableWitnesses(client);
        List<String> witnessIds = availableWitnesses.witnesses().stream()
                .map(WitnessInfo::eid)
                .toList();

        CreateIdentifierArgs kArgs = CreateIdentifierArgs.builder().build();
        kArgs.setToad(availableWitnesses.toad());
        kArgs.setWits(witnessIds);

        Optional<HabState> optionalIdentifier = client.identifiers().get(name);
        if (optionalIdentifier.isPresent()) {
            id = optionalIdentifier.get().getPrefix();
        } else {
            log.info("Creating identifier {} with toad {} and witnesses {}", name, availableWitnesses.toad(), witnessIds);
            EventResult result = client.identifiers().create(name, kArgs);
            Object op = result.op();
            op = client.operations().wait(Operation.fromObject(op));
            LinkedHashMap<String, Object> resp = (LinkedHashMap<String, Object>) (Operation.fromObject(op).getResponse());
            id = resp.get("i");

            if (client.getAgent() != null && client.getAgent().getPre() != null) {
                eid = client.getAgent().getPre();
            }

            if (eid != null && !eid.isEmpty() && !hasEndRole(client, name, "agent", eid)) {
                log.info("Adding agent endrole for identifier {} -> eid {}", name, eid);
                try {
                    EventResult results = client.identifiers().addEndRole(name, "agent", eid, null);
                    Object ops = results.op();
                    client.operations().wait(Operation.fromObject(ops));
                } catch (Exception e) {
                    log.warn("addEndRole failed: {}", e.getMessage());
                }
            }
        }

        return id != null ? id.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public static AvailableWitnesses getAvailableWitnesses(SignifyClient client) throws Exception {
        Map<String, Object> config = (Map<String, Object>) new Coring.Config(client).get();
        List<String> iurls = (List<String>) config.get("iurls");
        if (iurls == null) {
            throw new IllegalStateException("Agent configuration is missing iurls");
        }

        Map<String, WitnessInfo> witnessMap = new LinkedHashMap<>();
        for (String oobi : iurls) {
            try {
                new URL(oobi);
                String[] parts = oobi.split("/oobi/");
                if (parts.length > 1) {
                    String witnessEid = parts[1].split("/")[0];
                    witnessMap.putIfAbsent(witnessEid, new WitnessInfo(witnessEid, oobi));
                }
            } catch (Exception e) {
                log.warn("Error parsing oobi URL: {} - {}", oobi, e.getMessage());
            }
        }

        List<WitnessInfo> uniqueWitnesses = new ArrayList<>(witnessMap.values());
        int size = uniqueWitnesses.size();

        if (size >= 6) return new AvailableWitnesses(4, uniqueWitnesses.subList(0, 6));
        if (size > 0) return new AvailableWitnesses(size, uniqueWitnesses);

        throw new IllegalStateException("Insufficient witnesses available");
    }

    public static Boolean hasEndRole(SignifyClient client, String alias, String role, String eid) throws Exception {
        List<Map<String, Object>> list = getEndRoles(client, alias, role);
        for (Map<String, Object> endRoleMap : list) {
            String endRole = (String) endRoleMap.get("role");
            String endRoleEid = (String) endRoleMap.get("eid");
            if (endRole != null && endRoleEid != null && endRole.equals(role) && endRoleEid.equals(eid)) {
                return true;
            }
        }
        return false;
    }

    public static List<Map<String, Object>> getEndRoles(SignifyClient client, String alias, String role) throws Exception {
        String path = (role != null)
                ? "/identifiers/" + alias + "/endroles/" + role
                : "/identifiers/" + alias + "/endroles";
        HttpResponse<String> response = client.fetch(path, "GET", alias, null);
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }
}
