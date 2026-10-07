package org.cardanofoundation.cip113.offline;

import org.cardanofoundation.cip113.controller.AdminController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-099 — after a rotation, authority follows the CHAIN, not the registration row.
 *
 * <h2>The bug this pins</h2>
 *
 * {@code AdminController} used to decide the ISSUER_ADMIN role with
 * {@code pkh.equals(token.getIssuerAdminPkh())} — a column on the registration row that NO rotation
 * path updates, and that nothing can correctly update: the only hook is submit time, and a row
 * synced at submit records a rotation that may never confirm. There is no indexer-side RotateAdmin
 * handler.
 *
 * <p>So the wallet that registered the token kept ISSUER_ADMIN forever, and the admin who actually
 * holds {@code admin_credential_hash} after a rotation never got it — handed control on chain and
 * locked out of the panel.
 *
 * <h2>⛔ Why these tests cannot be satisfied while the feature stays broken</h2>
 *
 * The obvious test — "assert the new admin is authorised" — passes against the ALREADY-CORRECT path
 * in {@code RwaTokenController}, which has always read the live datum, while every row-reading
 * caller stays broken. That is this repo's documented failure class: a test that agrees with the
 * implementation's assumption rather than with the requirement.
 *
 * <p>The structural answer is that {@code issuerAdminPkh} is not a parameter of
 * {@link AdminController#rwaRoles}. A stale row cannot influence the decision because the decision
 * cannot see it. The cases below then drive the live value with the row deliberately irrelevant —
 * if someone reintroduces the row, this file stops compiling rather than silently passing.
 *
 * <p>⚠ {@code issuerAdminPkh} itself must NOT be migrated to the live credential. It is a SCRIPT
 * PARAMETER: {@code buildIssuerAdminScript} derives a hash from it that is already on chain, so
 * rewriting it would make every derived credential miss — the shape of the preprod 3141 incident.
 * It stays as the registration-time value, and it stays out of authority decisions.
 */
class RwaAdminAuthorityTest {

    private static final String OUTGOING = "a".repeat(56);
    private static final String INCOMING = "b".repeat(56);
    private static final String STRANGER = "c".repeat(56);
    private static final int NO_CAPS = 0;
    private static final int ADMIN_CAP = 0b00001;
    private static final int MINTER_ONLY = 0b00010;

    @Test
    @DisplayName("after a rotation the INCOMING admin is authorised, with no power-user row at all")
    void incomingAdminIsAuthorised() {
        // The live datum now names INCOMING. No capabilities, because a rotation grants no node.
        List<String> roles = AdminController.rwaRoles(INCOMING, INCOMING, NO_CAPS);
        assertTrue(roles.contains("ISSUER_ADMIN"),
                "the holder of admin_credential_hash must be ISSUER_ADMIN; got " + roles);
        assertTrue(roles.contains("BLACKLIST_MANAGER"),
                "denylist mutations are gated on the same credential; got " + roles);
    }

    @Test
    @DisplayName("after a rotation the OUTGOING admin is refused — even though the row still names them")
    void outgoingAdminIsRefused() {
        // ⛔ THE CASE THE OLD CODE GOT WRONG. The registration row still holds OUTGOING, and would
        // have granted the role. The decision cannot see the row, so the only input that matters is
        // the live credential, which is now INCOMING.
        List<String> roles = AdminController.rwaRoles(OUTGOING, INCOMING, NO_CAPS);
        assertEquals(List.of(), roles,
                "the previous admin must lose authority the moment the datum changes; got " + roles);
    }

    @Test
    @DisplayName("a power-user with the ADMIN capability keeps authority independently of the datum")
    void adminCapabilityIsAnIndependentGround() {
        // Mint, burn and pause check the caller's own power-user node on chain, not the GS datum,
        // so the two grounds are genuinely independent and must stay OR-ed.
        List<String> roles = AdminController.rwaRoles(STRANGER, INCOMING, ADMIN_CAP);
        assertTrue(roles.contains("ISSUER_ADMIN"), "ADMIN capability alone must grant it; got " + roles);
    }

    @Test
    @DisplayName("a non-admin capability grants nothing")
    void minterIsNotAnAdmin() {
        assertEquals(List.of(), AdminController.rwaRoles(STRANGER, INCOMING, MINTER_ONLY));
    }

    @Test
    @DisplayName("an unreadable datum WITHHOLDS authority rather than granting it")
    void unreadableDatumFailsClosed() {
        // ⛔ FAIL-CLOSED. readGlobalState returns empty when the provider is unreachable, the GS NFT
        // is not indexed yet, or the datum has an unexpected field count. A wallet seeing a token
        // vanish from /admin is visible and recoverable; silently granting admin is not.
        assertEquals(List.of(), AdminController.rwaRoles(INCOMING, null, NO_CAPS),
                "a failed chain read must not grant authority");
        assertEquals(List.of(), AdminController.rwaRoles(OUTGOING, null, NO_CAPS));
        // …but a real ADMIN capability still works, because that ground does not need the datum.
        assertTrue(AdminController.rwaRoles(STRANGER, null, ADMIN_CAP).contains("ISSUER_ADMIN"));
    }

    @Test
    @DisplayName("credential comparison is case-insensitive, and a null wallet is refused")
    void comparisonIsRobust() {
        assertTrue(AdminController.rwaRoles(INCOMING.toUpperCase(), INCOMING, NO_CAPS)
                .contains("ISSUER_ADMIN"), "hex case must not decide authority");
        assertEquals(List.of(), AdminController.rwaRoles(null, INCOMING, NO_CAPS));
        assertFalse(AdminController.rwaRoles(null, null, NO_CAPS).contains("ISSUER_ADMIN"),
                "null == null must not be read as a match");
    }
}
