package org.cardanofoundation.cip113.offline;

import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.cardanofoundation.cip113.model.KycExtendedRegisterRequest;
import org.cardanofoundation.cip113.model.KycRegisterRequest;
import org.cardanofoundation.cip113.model.MintTokenRequest;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.service.substandard.KycExtendedSubstandardHandler;
import org.cardanofoundation.cip113.service.substandard.KycSubstandardHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The refusals: paths that must reject CIP-68 metadata outright rather than quietly dropping it.
 *
 * <p>These need no chain. Every guard under test is the first statement of its method, before any
 * collaborator is touched, which is the point — a refusal that depended on resolving scripts or
 * UTxOs would fail open whenever those lookups failed, and "fails open" is exactly the property
 * that made these bugs possible.
 *
 * <p>Why this matters more than it looks: a {@code (222)}/{@code (333)} label is a <em>promise</em>
 * that a {@code (100)} reference token exists and resolves. Minting the labelled token while
 * discarding the metadata does not merely lose information — it publishes a token whose label
 * points a wallet at metadata that was never written. That is strictly worse than not labelling at
 * all, and it is what {@code kyc}/{@code kyc-extended} did on the mint endpoint: registration
 * refused, so the wizard could not reach it, but a direct API call sailed through.
 */
public class Cip68RefusalTest {

    private static final Cip68Metadata METADATA = new Cip68Metadata(
            "Refused Token", "should never reach the chain", "NOPE", 0, null, null);

    /** Every dependency is null: the guard must fire before any of them is dereferenced. */
    private static KycSubstandardHandler kycHandler() {
        return new KycSubstandardHandler(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static KycExtendedSubstandardHandler kycExtendedHandler() {
        return new KycExtendedSubstandardHandler(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static MintTokenRequest mintWithMetadata() {
        return new MintTokenRequest(
                "addr_test1qq0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                "aa".repeat(28),
                "4d59544b4e",
                "1000",
                null,
                null,
                METADATA);
    }

    // ── kyc ──────────────────────────────────────────────────────────────────

    @Test
    public void kycMintRefusesCip68Metadata() {
        TransactionContext<Void> result = kycHandler().buildMintTransaction(mintWithMetadata(), null);
        assertRefused("kyc mint", result, "kyc");
    }

    @Test
    public void kycRegistrationRefusesCip68Metadata() {
        var request = KycRegisterRequest.builder()
                .substandardId("kyc")
                .assetName("4d59544b4e")
                .quantity("1000")
                .cip68Metadata(METADATA)
                .build();
        assertRefused("kyc registration",
                kycHandler().buildRegistrationTransaction(request, null), "kyc");
    }

    /** The control: without metadata the guard must NOT fire, so the refusal cannot be a blanket
     *  rejection that happens to look right. Execution proceeds past it and fails later on a null
     *  collaborator — a different error, which is the observable difference being asserted. */
    @Test
    public void kycMintWithoutCip68MetadataIsNotRefusedByTheGuard() {
        var plain = new MintTokenRequest(
                "addr_test1qq0", "aa".repeat(28), "4d59544b4e", "1000", null, null, null);
        assertNotRefusedByTheCip68Guard(kycHandler().buildMintTransaction(plain, null));
    }

    // ── kyc-extended ─────────────────────────────────────────────────────────

    @Test
    public void kycExtendedMintRefusesCip68Metadata() {
        TransactionContext<Void> result =
                kycExtendedHandler().buildMintTransaction(mintWithMetadata(), null);
        assertRefused("kyc-extended mint", result, "kyc-extended");
    }

    @Test
    public void kycExtendedRegistrationRefusesCip68Metadata() {
        var request = KycExtendedRegisterRequest.builder()
                .substandardId("kyc-extended")
                .assetName("4d59544b4e")
                .quantity("1000")
                .cip68Metadata(METADATA)
                .build();
        assertRefused("kyc-extended registration",
                kycExtendedHandler().buildRegistrationTransaction(request, null), "kyc-extended");
    }

    @Test
    public void kycExtendedMintWithoutCip68MetadataIsNotRefusedByTheGuard() {
        var plain = new MintTokenRequest(
                "addr_test1qq0", "aa".repeat(28), "4d59544b4e", "1000", null, null, null);
        assertNotRefusedByTheCip68Guard(kycExtendedHandler().buildMintTransaction(plain, null));
    }

    private static void assertNotRefusedByTheCip68Guard(TransactionContext<?> result) {
        // It still fails — every collaborator is null — but it must fail for a DIFFERENT reason.
        // If this ever started reporting a CIP-68 refusal, the guard would be rejecting requests
        // that carry no metadata at all.
        String error = String.valueOf(result.error());
        Assertions.assertFalse(error.contains("CIP-68"),
                "a request with no cip68Metadata must not be refused by the CIP-68 guard, got: " + error);
    }

    private static void assertRefused(String what, TransactionContext<?> result, String substandardId) {
        Assertions.assertFalse(result.isSuccessful(), what + " must refuse CIP-68 metadata");
        Assertions.assertNull(result.unsignedCborTx(),
                what + " must not return a transaction when it refuses");
        String error = String.valueOf(result.error());
        Assertions.assertTrue(error.contains("CIP-68"),
                what + " error must name CIP-68, got: " + error);
        Assertions.assertTrue(error.contains(substandardId),
                what + " error must name the substandard, got: " + error);
        // The error is only useful if it says where CIP-68 DOES work.
        Assertions.assertTrue(error.contains("security-token"),
                what + " error must point at the supported substandards, got: " + error);
    }
}
