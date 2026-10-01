package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A withdraw-0 must target the credential the REGISTRATION registered, which the chain records.
 *
 * <h2>The failure</h2>
 *
 * Measured on preprod 2026-10-01. Giovanni issued an FES token with this backend, then could not
 * transfer it: ledger <strong>3141</strong>, culprit reward account
 * {@code stake_test17z3kxakn9fcmphzkmyfmm2meagfa6gmmymce7laww5grvcszhtft2} = script credential
 * {@code a36376d3…}. The chain's registry node for that token records
 * {@code transferLogicScript = 83764aa7…}, and the blacklist init registered {@code 83764aa7…} too.
 * So the init and the chain agreed and only the transfer's own derivation was wrong — and it was
 * submitted anyway, because nothing compared the two.
 *
 * <p>Derivation proved the inputs: with the real blacklist policy {@code c5fe9cb9…},
 * {@code buildTransfer(preprod base d255fd34…)} reproduces {@code 83764aa7…} exactly, while
 * preview's base gives {@code c7d84e64…} — neither yields {@code a36376d3…}, which is how the
 * cross-environment explanation was ruled out.
 *
 * <h2>⛔ Why this REFUSES rather than using the chain's value</h2>
 *
 * "Trust the chain" is right about truth and wrong about bytes. A withdraw-0 from a script
 * credential must carry the SCRIPT, not its hash. On a mismatch the backend holds the chain's hash
 * and no script that hashes to it, so substituting it cannot build a submittable transaction — it
 * trades 3141 for a phase-1 witness error at the same stage, after the same signature. Refusal is
 * the only outcome that tells the operator something actionable. {@code kyc-extended} reached this
 * conclusion independently and already refuses.
 */
class RegistryCredentialCrossCheckTest {

    /** A real preprod value: the FES module transfer the chain records for Giovanni's token. */
    private static final String ON_CHAIN = "83764aa7fdcd26e691251f3f25c646e23bac7abab076cbd7fa981495";

    /**
     * An instance with every collaborator null. The cross-check touches no field but the logger, so
     * constructing it this way keeps the test offline — no Spring, no repositories, no chain.
     */
    private static FreezeAndSeizeHandler handlerWithNoCollaborators() throws Exception {
        Constructor<?> widest = null;
        for (Constructor<?> c : FreezeAndSeizeHandler.class.getDeclaredConstructors()) {
            if (widest == null || c.getParameterCount() > widest.getParameterCount()) widest = c;
        }
        assertNotNull(widest, "FreezeAndSeizeHandler has no constructor");
        widest.setAccessible(true);
        return (FreezeAndSeizeHandler) widest.newInstance(new Object[widest.getParameterCount()]);
    }

    /** A script whose hash is whatever we need it to be is impossible, so derive the hash from one. */
    private static PlutusScript scriptWithKnownHash() {
        // Any valid V3 script; the test compares against ITS hash, never a literal.
        return PlutusV3Script.builder().cborHex("4e4d01000033222220051200120011").build();
    }

    @Test
    @DisplayName("agreement produces no refusal")
    void agreementIsSilent() throws Exception {
        var handler = handlerWithNoCollaborators();
        var script = scriptWithKnownHash();
        var sameHash = HexUtil.encodeHexString(script.getScriptHash());

        var result = handler.registryCredentialMismatch(
                "transfer", "policy", Credential.fromScript(sameHash), "transferLogicScript",
                script, "inputs", "txhash");

        assertTrue(result.isEmpty(), "when the derivation matches the chain there is nothing to refuse");
    }

    @Test
    @DisplayName("a mismatch refuses, and names BOTH hashes and the derivation inputs")
    void mismatchRefusesAndExplains() throws Exception {
        var handler = handlerWithNoCollaborators();
        var script = scriptWithKnownHash();
        var derived = HexUtil.encodeHexString(script.getScriptHash());
        assertNotEquals(ON_CHAIN, derived, "the fixture must actually disagree, or this proves nothing");

        var result = handler.registryCredentialMismatch(
                "transfer", "949ac9dd", Credential.fromScript(ON_CHAIN), "transferLogicScript",
                script, "programmableLogicBase=d255fd34, blacklistNodePolicyId=c5fe9cb9", "dd6d13d1");

        assertTrue(result.isPresent(), "a disagreement with the chain must refuse, not proceed");
        String msg = result.get();
        // Both hashes: without them the operator cannot tell which side is wrong.
        assertTrue(msg.contains(ON_CHAIN), "the refusal must quote the chain's hash; got: " + msg);
        assertTrue(msg.contains(derived), "the refusal must quote the derived hash; got: " + msg);
        // The inputs: this is what makes the cause findable instead of a mystery.
        assertTrue(msg.contains("programmableLogicBase=d255fd34"), "must name the derivation inputs");
        assertTrue(msg.contains("blacklistNodePolicyId=c5fe9cb9"), "must name the blacklist policy used");
        assertTrue(msg.contains("dd6d13d1"), "must name the protocol deployment");
        // And it must say why substitution is not the answer, or someone will try it.
        assertTrue(msg.contains("withdraw-0 must carry the SCRIPT"),
                "the refusal should explain why the on-chain hash cannot simply be used; got: " + msg);
    }

    @Test
    @DisplayName("a non-script or wrong-length credential is 'no evidence', never a refusal")
    void unsetCredentialDoesNotRefuse() throws Exception {
        var handler = handlerWithNoCollaborators();
        var script = scriptWithKnownHash();

        // ⛔ EMPTY_VKEY is the registry's documented "unset" sentinel: a ZERO-LENGTH KEY credential.
        // Treating it as a mismatch would refuse valid operations on tokens that record no hook.
        for (Credential unset : new Credential[] {
                null,
                Credential.fromKey(new byte[0]),
                Credential.fromKey(HexUtil.decodeHexString(ON_CHAIN)),   // right length, KEY type
                Credential.fromScript(new byte[4]),                       // script type, wrong length
        }) {
            var result = handler.registryCredentialMismatch(
                    "transfer", "policy", unset, "transferLogicScript", script, "inputs", "txhash");
            assertTrue(result.isEmpty(),
                    "a credential that is not a 28-byte SCRIPT hash carries no evidence and must not "
                            + "refuse: " + unset);
        }
    }

    @Test
    @DisplayName("the comparison is case-insensitive, so hex casing cannot cause a false refusal")
    void hexCasingDoesNotMatter() throws Exception {
        var handler = handlerWithNoCollaborators();
        var script = scriptWithKnownHash();
        var upper = HexUtil.encodeHexString(script.getScriptHash()).toUpperCase();

        var result = handler.registryCredentialMismatch(
                "transfer", "policy", Credential.fromScript(upper), "transferLogicScript",
                script, "inputs", "txhash");

        assertTrue(result.isEmpty(), "the same hash in different case is the same hash");
    }
}
