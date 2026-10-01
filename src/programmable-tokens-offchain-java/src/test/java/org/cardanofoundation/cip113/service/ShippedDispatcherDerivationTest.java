package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cardanofoundation.cip113.core.CoreBlueprint;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.core.CoreValidator;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the records shipped to deployments, not test resources that shadow their names. */
class ShippedDispatcherDerivationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CoreScriptFactory scripts = new CoreScriptFactory(new CoreBlueprint());

    private ProtocolBootstrapParams shipped(String network) throws Exception {
        var json = Files.readString(Path.of("src/main/resources/protocol-bootstraps-" + network + ".json"));
        return mapper.readTree(json).get(0).traverse(mapper).readValueAs(ProtocolBootstrapParams.class);
    }

    private ProtocolBootstrapParams changed(ProtocolBootstrapParams original, String field, String value) throws Exception {
        ObjectNode node = mapper.valueToTree(original);
        ((ObjectNode) node.get("programmableLogicGlobal")).put(field, value);
        return mapper.treeToValue(node, ProtocolBootstrapParams.class);
    }

    @Test
    void preprodDisabledUnfrackingDerivesThePublishedDispatcher() throws Exception {
        var preprod = shipped("preprod");
        assertEquals("0".repeat(56), preprod.programmableLogicGlobal().unfrackingParameter());
        assertEquals("24ef08d8996d06bca30602381dc30be20bfe7e31c495f2caf3ae6470",
                scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, preprod).getPolicyId());
    }

    @Test
    void previewEnabledUnfrackingDerivesThePublishedDispatcher() throws Exception {
        var preview = shipped("preview");
        assertEquals(preview.unfracking().scriptHash(),
                preview.programmableLogicGlobal().unfrackingParameter());
        assertEquals(preview.programmableLogicGlobal().scriptHash(),
                scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, preview).getPolicyId());
    }

    @Test
    void missingMalformedAndMismatchedParametersAreRejectedEvenAfterCacheWarmup() throws Exception {
        var preprod = shipped("preprod");
        scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, preprod);

        for (String parameter : new String[]{null, "xyz", "0".repeat(54)}) {
            var changed = changed(preprod, "unfrackingParameter", parameter);
            var error = assertThrows(IllegalStateException.class,
                    () -> scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, changed));
            assertTrue(error.getMessage().contains("unfrackingParameter"));
        }

        var wrongParameter = changed(preprod, "unfrackingParameter", preprod.unfracking().scriptHash());
        var parameterError = assertThrows(IllegalStateException.class,
                () -> scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, wrongParameter));
        assertTrue(parameterError.getMessage().contains("records programmableLogicGlobal.scriptHash"));

        var wrongHash = changed(preprod, "scriptHash", "f".repeat(56));
        var hashError = assertThrows(IllegalStateException.class,
                () -> scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, wrongHash));
        assertTrue(hashError.getMessage().contains("records programmableLogicGlobal.scriptHash"));
    }
}
