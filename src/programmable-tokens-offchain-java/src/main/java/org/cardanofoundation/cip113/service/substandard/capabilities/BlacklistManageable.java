package org.cardanofoundation.cip113.service.substandard.capabilities;

import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.TransactionContext.MintingResult;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;

/**
 * Blacklist management capability for freeze-and-seize functionality.
 * Adding an address to the blacklist effectively "freezes" it (prevents transfers).
 * Removing from blacklist "unfreezes" the address.
 */
public interface BlacklistManageable {

    /**
     * Request to initialize a blacklist for a programmable token.
     * Requires the token to be already registered in the programmable token registry.
     */
    record BlacklistInitRequest(
            String substandardId,
            /** The admin address that will manage this blacklist */
            String adminAddress,
            /** The address that pays for the tx */
            String feePayerAddress,
            /** Hex-encoded asset name of the programmable token, WITHOUT any CIP-67 label */
            String assetName,
            /**
             * The supply the matching registration will request. Needed here only to pick the
             * CIP-67 label — see {@link #cip68Metadata}.
             */
            String quantity,
            /**
             * Must match the {@code cip68Metadata} of the registration this blacklist belongs to.
             *
             * <p>This transaction registers the {@code issuer_admin} reward account that the
             * registration later withdraws-0 from, and {@code issuer_admin} is parameterized by
             * the asset name — so a CIP-68 registration withdraws from a <em>different</em> reward
             * address than an unlabelled one. If this request did not know about the label, it
             * would register the wrong credential and the registration would be rejected with
             * {@code WithdrawalsNotInRewardsCERTS}, after the user had already signed and paid
             * for this transaction.
             *
             * <p>Only its presence and the {@code quantity} matter here; the metadata itself is
             * written by the registration, not by this transaction.
             */
            org.cardanofoundation.cip113.model.Cip68Metadata cip68Metadata
    ) {
    }

    /**
     * Request to add an address to the blacklist (freeze).
     */
    record AddToBlacklistRequest(
            /**
             * The policy id of the programmable token
             */
            String tokenPolicyId,
            /** Hex-encoded asset name of the programmable token */
            String assetName,
            /** The address/credential to add to blacklist */
            String targetAddress,
            /** The address that pays for hte tx */
            String feePayerAddress
    ) {
    }

    /**
     * Request to remove an address from the blacklist (unfreeze).
     */
    record RemoveFromBlacklistRequest(
            /**
             * The policy id of the programmable token
             */
            String tokenPolicyId,
            /** Hex-encoded asset name of the programmable token */
            String assetName,
            /** The address/credential to remove from blacklist */
            String targetAddress,
            /** The address that pays for hte tx */
            String feePayerAddress
    ) {
    }

    /**
     * Initialize a new blacklist for a programmable token.
     * This creates the on-chain linked list structure for tracking blacklisted addresses.
     *
     * @param request        The blacklist initialization request
     * @param protocolParams The protocol bootstrap parameters
     * @return Transaction context with unsigned CBOR tx and bootstrap parameters
     */
    TransactionContext<MintingResult> buildBlacklistInitTransaction(
            BlacklistInitRequest request,
            ProtocolBootstrapParams protocolParams);

    /**
     * Add an address/credential to the blacklist (freeze).
     * Once blacklisted, the address cannot transfer programmable tokens.
     *
     * @param request        The add to blacklist request
     * @param protocolParams The protocol bootstrap parameters
     * @return Transaction context with unsigned CBOR tx
     */
    TransactionContext<Void> buildAddToBlacklistTransaction(
            AddToBlacklistRequest request,
            ProtocolBootstrapParams protocolParams);

    /**
     * Remove an address/credential from the blacklist (unfreeze).
     * Once removed, the address can transfer programmable tokens again.
     *
     * @param request        The remove from blacklist request
     * @param protocolParams The protocol bootstrap parameters
     * @return Transaction context with unsigned CBOR tx
     */
    TransactionContext<Void> buildRemoveFromBlacklistTransaction(
            RemoveFromBlacklistRequest request,
            ProtocolBootstrapParams protocolParams);
}
