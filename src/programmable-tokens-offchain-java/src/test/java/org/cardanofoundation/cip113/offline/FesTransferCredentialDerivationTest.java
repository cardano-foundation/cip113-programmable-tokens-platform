package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Derive the FES transfer credential under BOTH preprod deployments, so the chain can be asked which
 * one is registered.
 *
 * <p>The FES {@code transfer} validator is parameterised by
 * {@code (programmableLogicBase.scriptHash, blacklistNodePolicyId)}, so it carries a hash from the
 * CORE deployment. preprod was re-bootstrapped on 2026-09-30 ({@code 31e3844}) and every core hash
 * moved. This prints the reward address each deployment yields for the known blacklists, and asserts
 * the two genuinely differ — which is the structural claim the hypothesis rests on.
 *
 * <p>⚑ USES THE PLATFORM'S OWN BUILDER, deliberately. Re-implementing Aiken parameter application
 * here would prove a reimplementation agrees with itself; this proves what the service actually
 * derives, which is what the chain has to be asked about.
 */
class FesTransferCredentialDerivationTest {

    /** preprod, before 31e3844 — the deployment the older blacklists were initialised under. */
    private static final String PROG_LOGIC_BASE_OLD =
            "feae586b49b345c78cf110cb24bd380c65e9f2f346017543d52ec9ae";
    /** preprod, current. */
    private static final String PROG_LOGIC_BASE_NEW =
            "d255fd34ae145421b823481f5860b16af1ac2a5664e1b12dfdbeffb5";

    /** The blacklists visible in Giovanni's preprod database. */
    private static final String[][] BLACKLISTS = {
            { "cadaa513a1544126dd138e9c3dd8db2d7d3bb57c59a8b25b8a2743ec", "fes-token-2 / f4317346" },
            { "c20e6e20d0a894f77b90c8833ddad2dc61e9610a56bd934a3bb704dc", "FESToken / 2215a41c" },
    };

    @Test
    @DisplayName("the transfer credential differs between the two deployments — print both for a chain query")
    void printBothDeployments() throws Exception {
        var fes = new FreezeAndSeizeScriptBuilderService(HandlerFixtures.moduleService());

        System.out.println("=== FES transfer credential, per blacklist, per deployment (preprod) ===");
        for (var bl : BLACKLISTS) {
            var policy = bl[0];
            var oldAddr = reward(fes, PROG_LOGIC_BASE_OLD, policy);
            var newAddr = reward(fes, PROG_LOGIC_BASE_NEW, policy);

            System.out.println("blacklist " + policy + "   (" + bl[1] + ")");
            System.out.println("   OLD deployment feae586b… -> " + oldAddr);
            System.out.println("   NEW deployment d255fd34… -> " + newAddr);
            // And the blacklist SPEND address, so the chain can be asked whether any node exists —
            // the second candidate cause: a blacklist that was never initialised at all. Only
            // transfer and freeze need a non-membership proof, so such a token passes bootstrap,
            // register, issue and dummy transfer, and fails first on the FES transfer.
            System.out.println("   blacklist spend address   -> " + AddressProvider.getEntAddress(
                    fes.buildBlacklistSpendScript(policy), Networks.testnet()).getAddress());

            // ⛔ IF THESE WERE EQUAL THE WHOLE HYPOTHESIS WOULD BE VOID — the credential would not
            // depend on the deployment and a re-bootstrap could not orphan it.
            assertNotEquals(oldAddr, newAddr,
                    "the transfer credential is identical across two different programmableLogicBase "
                            + "hashes, so it does not depend on the deployment and a re-bootstrap "
                            + "cannot invalidate it. That would kill the stale-credential hypothesis.");
        }
    }

    private static String reward(FreezeAndSeizeScriptBuilderService fes, String progLogicBase, String blacklistPolicy) {
        var script = fes.buildTransferScript(progLogicBase, blacklistPolicy);
        return AddressProvider.getRewardAddress(script, Networks.testnet()).getAddress();
    }
}
