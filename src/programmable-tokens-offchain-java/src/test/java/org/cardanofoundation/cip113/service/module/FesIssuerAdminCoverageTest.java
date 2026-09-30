package org.cardanofoundation.cip113.service.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blacklist covers exactly ONE {@code (adminPkh, assetName)} pair, and registration must refuse
 * any other rather than build a transaction the ledger will reject.
 *
 * <p>⛔ WHAT WENT WRONG, measured on preprod 2026-09-30. {@code issuer_admin} is parameterised by
 * {@code (adminPkh, ASSET NAME)} and the blacklist init is the ONLY place its reward account gets
 * registered — the ledger applies withdrawals before certificates, so the registration transaction
 * cannot register the account it withdraws from. A registration whose admin or asset name differed
 * from the init's therefore withdrew-0 from an account nothing had ever registered, and the ledger
 * refused it as code <strong>3141</strong>, whose message reads
 * <em>"rewards withdrawals must consume rewards in full"</em> — a balance problem — and mentions the
 * missing registration only in its final sentence, after the operator has signed and paid for the
 * init.
 *
 * <p>Two ways to fall off the pair, both silent before this:
 * <ul>
 *   <li>a different ADMIN — reusing a blacklist created by another wallet. An init covered
 *       {@code 438e0a0a…}; a later registration arrived with {@code 7eb45c3e…}.</li>
 *   <li>a different ASSET NAME — a SECOND token against one blacklist. Two registrations sharing one
 *       {@code blacklist_node_policy_id} were already present in that database.</li>
 * </ul>
 *
 * <p>⚑ V14 closed the LABEL dimension of this same script, because that is the variant somebody hit
 * first. The admin and asset-name dimensions were left open, and they are parameters of the same
 * script. That is the shape worth remembering: a guard written for one parameter of a function does
 * not cover the others.
 *
 * <p>⚠ A SOURCE-SCANNING CHECK, deliberately. Exercising the real paths needs a funded wallet, a
 * chain and two signed transactions minutes apart — which is why this defect reached production
 * twice. What is checkable cheaply is that the guards exist and that neither has been reduced to the
 * comparison it replaces, and the anchors below are chosen so that deleting a guard removes them.
 */
class FesIssuerAdminCoverageTest {

    private static String read(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    private static final String HANDLER =
            "src/main/java/org/cardanofoundation/cip113/service/module/FreezeAndSeizeHandler.java";
    private static final String CALLBACK =
            "src/main/java/org/cardanofoundation/cip113/controller/TokenContextController.java";
    private static final String ENTITY =
            "src/main/java/org/cardanofoundation/cip113/entity/BlacklistInitEntity.java";

    @Test
    @DisplayName("the init RECORDS which issuer_admin it registered, not merely that it was labelled")
    void initRecordsTheCoveredCredential() throws IOException {
        assertTrue(read(ENTITY).contains("issuerAdminStakeAddress"),
                "BlacklistInitEntity no longer carries issuerAdminStakeAddress. admin_pkh alone cannot "
                        + "answer which issuer_admin an init covered — it holds one of the two "
                        + "parameters and not the other.");
        assertTrue(read(HANDLER).contains(".issuerAdminStakeAddress(moduleIssueAddress.getAddress())"),
                "the blacklist init no longer records the issuer_admin address it registered, so "
                        + "registration has nothing to compare against");
    }

    @Test
    @DisplayName("registration refuses when its issuer_admin is not the one the init registered")
    void registrationComparesAgainstTheInit() throws IOException {
        var handler = read(HANDLER);
        assertTrue(handler.contains("getIssuerAdminStakeAddress()"),
                "registration no longer compares its issuer_admin against the init's recorded one");
        assertTrue(handler.contains("expectedIssuerAdmin"),
                "the derived expected issuer_admin is gone from the registration path");
        // ⛔ AND IT MUST ALSO ASK THE CHAIN. The comparison cannot fire on rows written before the
        // column existed — and those are exactly the rows written while nothing checked. It also
        // cannot catch an init that SKIPPED the certificate because isStakeAddressRegistered
        // answered true for a credential that was not registered; that function has no "unknown"
        // state, so a wrong true silently omits a certificate.
        assertTrue(handler.contains("isStakeAddressRegistered(expectedIssuerAdmin)"),
                "registration no longer verifies on chain that the issuer_admin reward account is "
                        + "actually registered. The recorded-address comparison is NULL-blind for "
                        + "older rows, which are the likeliest to be wrong.");
    }

    @Test
    @DisplayName("the registration callback DERIVES the policy id instead of trusting the caller")
    void theCallbackDerivesBeforeStoring() throws IOException {
        var callback = read(CALLBACK);
        assertTrue(callback.contains("getParameterizedIssuanceMintScript"),
                "the token-registration callback no longer derives the policy id. policyId, "
                        + "issuerAdminPkh and assetName are not independent — the policy id IS the "
                        + "hash of issuance_mint parameterised by issuer_admin(adminPkh, assetName) — "
                        + "so storing them unverified records a row describing a different token, or "
                        + "one that was never minted. Both were found on preprod.");
        assertTrue(callback.contains("derivedFromSuppliedPair"),
                "the callback's refusal no longer reports what the supplied pair actually derives, "
                        + "which is the one value that makes the failure diagnosable");
        // ⚑ A derivation that cannot RUN must refuse too. Falling back to storing unverified would
        // reinstate exactly the trust this guard replaces, and it would do it silently.
        assertTrue(callback.contains("could not verify the supplied policyId"),
                "the callback no longer refuses when the derivation itself fails — an unavailable "
                        + "blueprint would silently restore the old trust-the-caller behaviour");
    }

    @Test
    @DisplayName("the TRANSFER credential is recorded and checked — it is pinned to the DEPLOYMENT")
    void transferCredentialIsPinnedToTheDeployment() throws IOException {
        // ⛔ A DIFFERENT DEPENDENCY FROM issuer_admin, and that asymmetry is the finding.
        //   issuer_admin = (adminPkh, ASSET NAME)                               -- no core hash
        //   transfer     = (programmableLogicBase.scriptHash, blacklistPolicy)  -- CARRIES one
        // So a protocol re-bootstrap moves every core hash, invalidating `transfer` while leaving
        // `issuer_admin` untouched. Measured on preprod 2026-09-30: after programmableLogicBase moved
        // feae586b… -> d255fd34…, ISSUING an FES token still worked and TRANSFERRING it failed —
        // and nothing in the error said why, because the ledger reports a rewards-balance problem.
        assertTrue(read(ENTITY).contains("moduleTransferStakeAddress"),
                "BlacklistInitEntity no longer records the transfer credential. Without it, a "
                        + "re-bootstrap silently invalidates every pre-existing blacklist's transfer "
                        + "path while issuing keeps working.");
        var handler = read(HANDLER);
        assertTrue(handler.contains(".moduleTransferStakeAddress(moduleTransferAddress.getAddress())"),
                "the blacklist init no longer records which transfer credential it registered");
        // ⛔ ANCHOR ON THE CONDITION, NOT THE ACCESSOR NAME. A first version asserted only that
        // `getModuleTransferStakeAddress()` appeared somewhere — which stays true inside
        // `if (false && initRow.get().getModuleTransferStakeAddress() != null)`. The mutant that
        // disables the guard left the string in place and the check passed. The condition is what the
        // defect has to alter, so that is what gets asserted.
        assertTrue(handler.contains(
                        "if (initRow.isPresent() && initRow.get().getModuleTransferStakeAddress() != null"),
                "the transfer path no longer GUARDS on the init's recorded transfer credential. The "
                        + "accessor appearing somewhere is not enough — it survives inside a disabled "
                        + "condition, which is how this check was blind the first time it was written.");
        assertTrue(handler.contains("RE-BOOTSTRAPPED"),
                "the refusal no longer names the re-bootstrap as the usual cause — which is the one "
                        + "thing that turns a rewards-balance message into an actionable answer");
    }

    @Test
    @DisplayName("the check is not vacuous — every anchor it asserts is a distinct, present string")
    void theCheckCanFail() throws IOException {
        // ⚑ PROOF OF HARNESS. Each assertion above is an `assertTrue(contains(...))`, which passes
        // silently if the file were empty, unreadable-as-expected, or renamed. Confirm the files are
        // real and substantial, so a passing suite means the guards are present rather than that the
        // search found nothing to object to.
        for (var p : new String[] { HANDLER, CALLBACK, ENTITY }) {
            var body = read(p);
            assertTrue(body.length() > 2000, p + " is unexpectedly small; these checks would be blind");
            assertTrue(body.contains("class ") || body.contains("record "),
                    p + " does not look like the Java source this suite expects");
        }
        // And the anchor that the defect itself would remove: without the column there is no guard.
        assertTrue(read(ENTITY).contains("issuer_admin_stake_address"),
                "the column name is gone from the entity mapping, so the guards above cannot bind");
    }
}
