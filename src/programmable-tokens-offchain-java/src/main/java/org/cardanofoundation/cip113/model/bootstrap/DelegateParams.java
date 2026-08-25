package org.cardanofoundation.cip113.model.bootstrap;

/**
 * A deployed withdraw-0 delegate: {@code transfer}, {@code third_party} or
 * {@code unfracking}.
 *
 * <p>All three are parameterised identically — by the protocol-params NFT policy, the
 * protocol's one permanent anchor — and all three are deployed the same way: compile,
 * publish as a reference script, register the stake credential, and write the credential
 * into the protocol-params datum. So they share one record rather than three
 * indistinguishable ones. (Before the coordinator was dissolved there was one delegate and
 * one record, {@code ProgrammableLogicGlobalParams}, plus a separate near-identical
 * {@code UnfrackingParams}.)
 *
 * <p>These recorded hashes are the values written at DEPLOYMENT. They are not authoritative
 * afterwards: an in-place upgrade rewrites the delegate credentials in the protocol-params
 * datum on chain and does not touch this file. Code that needs the current delegate must
 * read the datum — see {@code CoreProtocolParamsDatum}.
 *
 * @param protocolParamsPolicyId the params NFT policy this delegate was parameterised with
 * @param scriptHash             the delegate's script hash, i.e. its stake credential
 * @param rewardAddress          the bech32 reward address its withdraw-0 is made against.
 *                               Recorded rather than re-derived because deriving it needs
 *                               the network, and a record that is silently network-specific
 *                               is worse than one that states its answer.
 */
public record DelegateParams(String protocolParamsPolicyId, String scriptHash, String rewardAddress) {
}
