package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.onchain.PlutusCredentialCodec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The protocol-params NFT's inline datum — aiken's
 * {@code programmable_logic/params.ProgrammableLogicGlobalParams}.
 *
 * <p>This is the protocol's <em>live wiring</em>. Every programmable-token transaction
 * carries this UTxO as a reference input, and {@code programmable_logic_base} reads a
 * delegate credential out of it on every single spend. It is a datum rather than a set of
 * compile-time parameters precisely so a delegate can be swapped by rewriting it, without
 * moving PLB's hash and with it every programmable token address.
 *
 * <p>Which means: <strong>the deployment record in {@code protocol-bootstraps-*.json} is
 * not authoritative about who the delegates are.</strong> It records what was written at
 * deployment; an in-place upgrade changes the chain and not that file. Anything that needs
 * to know the current delegate must read this datum.
 *
 * <h2>Field layout (7 fields)</h2>
 *
 * <pre>
 *   0  registry_node_cs        PolicyId     registry-node NFT policy
 *   1  prog_logic_cred         Credential   PLB's payment credential
 *   2  transfer_cred           Credential   the `transfer` withdraw-0 validator
 *   3  third_party_cred        Credential   the `third_party` withdraw-0 validator
 *   4  unfracking_cred         Credential   the `unfracking` withdraw-0 validator
 *   5  upgrade_cred            Credential   who may rewrite this datum
 *   6  max_inline_datum_bytes  Int          bound on holder-created PLB output datums
 * </pre>
 *
 * <p>Upstream orders these by read frequency: PLB reads fields 2-4 on every input, so they
 * sit shallow; {@code upgrade_cred} is read only by {@code coordination_spend} on an
 * upgrade transaction, and {@code max_inline_datum_bytes} is read once per delegate
 * invocation rather than per input, so both are cheap to place last.
 *
 * <h2>This layout is not an extension of the previous one</h2>
 *
 * The 5-field predecessor was
 * {@code {registry_node_cs, prog_logic_cred, unfracking_cred, prog_logic_global_cred, upgrade_logic_cred}}.
 * Fields 2 and 3 <em>swapped roles</em>: what used to be {@code unfracking_cred} at index 2
 * is now {@code transfer_cred}. A decoder that tolerated a short field list, or an encoder
 * that appended rather than reordering, would produce a datum in which PLB dispatches
 * transfers to the unfracking validator. That is why {@link #from(PlutusData)} insists on
 * the exact field count instead of reading what it can.
 */
public record CoreProtocolParamsDatum(
        String registryNodePolicyId,
        Credential progLogicCred,
        Credential transferCred,
        Credential thirdPartyCred,
        Credential unfrackingCred,
        Credential upgradeCred,
        long maxInlineDatumBytes) {

    /** Field count at the vendored revision. Was 5 before the validator split. */
    public static final int FIELD_COUNT = 7;

    /** The token name of the protocol-params NFT ({@code params.protocol_params_token}). */
    public static final String TOKEN_NAME = "ProtocolParams";

    /**
     * Default bound for {@code max_inline_datum_bytes} at deployment.
     *
     * <p>What it does and does not buy, precisely, because the natural reading is too
     * generous: it bounds the inline datum of PLB outputs created by the <em>holder-driven</em>
     * paths — the transfer gate, and the fresh destination outputs of a third-party or
     * unfracking action. It does <strong>not</strong> bound the mint path, and it does not
     * bound a seizure's paired continuation output, which must reproduce its input's datum
     * byte for byte with no size check at all. So it does not guarantee that every PLB UTxO
     * is seizable within {@code maxTxSize}; it guarantees that no <em>holder</em> can create
     * one that is not.
     *
     * <p>An issuer still can, by minting one — which is outside the threat model, but is
     * directly relevant here, because the rwa-token substandard mints CIP-68 reference
     * tokens to PLB addresses carrying metadata. Bounding those belongs in that
     * substandard's minting logic, which is the only code that can distinguish an
     * issuer-driven mint from a user-driven one.
     *
     * <p>2048 leaves room for a realistic transfer datum without letting a holder make a
     * seizure expensive. It is a datum field rather than a constant so it can be retuned by
     * an upgrade rather than a redeploy.
     */
    public static final long DEFAULT_MAX_INLINE_DATUM_BYTES = 2048L;

    /** Decode from a hex-encoded inline datum, as stored by the chain indexer. */
    public static CoreProtocolParamsDatum fromHex(String inlineDatumHex) {
        try {
            return from(PlutusData.deserialize(HexUtil.decodeHexString(inlineDatumHex)));
        } catch (Exception e) {
            throw new IllegalArgumentException("not a decodable protocol-params datum: " + inlineDatumHex, e);
        }
    }

    /**
     * Decode, insisting on the exact shape.
     *
     * @throws IllegalArgumentException if the datum is not a constructor, or does not carry
     *         exactly {@link #FIELD_COUNT} fields. The field-count check is the load-bearing
     *         one: because the previous layout REORDERED rather than extended, a lenient
     *         positional read of a 5-field datum returns credentials for the wrong roles
     *         rather than failing.
     */
    public static CoreProtocolParamsDatum from(PlutusData datum) {
        if (!(datum instanceof ConstrPlutusData constr)) {
            throw new IllegalArgumentException("protocol-params datum is not a constructor: " + datum);
        }
        // ProgrammableLogicGlobalParams is a single-constructor record, so its alternative is
        // always 0. Checking it costs nothing and closes the case where some OTHER type with
        // seven compatible-looking fields decodes cleanly into the protocol's live wiring.
        if (constr.getAlternative() != 0) {
            throw new IllegalArgumentException(
                    "protocol-params datum has constructor alternative " + constr.getAlternative()
                            + ", expected 0 — this is not a ProgrammableLogicGlobalParams");
        }
        List<PlutusData> f = constr.getData().getPlutusDataList();
        if (f.size() != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "protocol-params datum has " + f.size() + " fields, expected " + FIELD_COUNT
                            + ". The blueprint on the classpath and the datum on chain disagree about the "
                            + "protocol's shape. A 5-field datum is the pre-split layout, in which field 2 "
                            + "is unfracking_cred rather than transfer_cred — decoding it positionally "
                            + "would report the unfracking validator as the transfer delegate. That "
                            + "deployment needs re-deploying, not re-reading; see docs/CORE-UPGRADE-PLAN.md.");
        }
        if (!(f.get(0) instanceof BytesPlutusData registryNodeCs)) {
            throw new IllegalArgumentException("protocol-params field 0 (registry_node_cs) is not a bytestring");
        }
        if (!(f.get(6) instanceof BigIntPlutusData maxDatumBytes)) {
            throw new IllegalArgumentException("protocol-params field 6 (max_inline_datum_bytes) is not an integer");
        }
        return new CoreProtocolParamsDatum(
                HexUtil.encodeHexString(registryNodeCs.getValue()),
                PlutusCredentialCodec.fromPlutusData(f.get(1), "prog_logic_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(2), "transfer_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(3), "third_party_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(4), "unfracking_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(5), "upgrade_cred"),
                maxDatumBytes.getValue().longValueExact());
    }

    /** Encode for the deployment transaction that mints the protocol-params NFT. */
    public PlutusData toPlutusData() {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(registryNodePolicyId)),
                PlutusCredentialCodec.toPlutusData(progLogicCred),
                PlutusCredentialCodec.toPlutusData(transferCred),
                PlutusCredentialCodec.toPlutusData(thirdPartyCred),
                PlutusCredentialCodec.toPlutusData(unfrackingCred),
                PlutusCredentialCodec.toPlutusData(upgradeCred),
                BigIntPlutusData.of(BigInteger.valueOf(maxInlineDatumBytes)));
    }

    /**
     * Refuse to deploy a datum that would brick or weaken the protocol.
     *
     * <p>None of this is checked on chain at MINT time: {@code protocol_params_mint} only
     * deserialises the datum, which shape-checks it and nothing more. {@code coordination_spend}
     * does guard credential lengths on an <em>upgrade</em>, but the initial mint has no such
     * gate, so the only place these can be caught is here, before the transaction is built.
     *
     * <p>Three properties, each with a distinct failure mode:
     *
     * <ul>
     *   <li><strong>28-byte credentials.</strong> A reward account is a header byte plus a
     *       28-byte hash, so a wrong-length credential can never appear in
     *       {@code tx.withdrawals} — nothing could ever satisfy it. Written into
     *       {@code transfer_cred} that makes every programmable token permanently unspendable;
     *       into {@code upgrade_cred} it makes the datum permanently unrewritable, removing
     *       the repair path as well. One-way, both of them.</li>
     *   <li><strong>Pairwise-distinct delegates.</strong> PLB's three-arm dispatch is
     *       self-validating only because a wrong arm resolves to a different credential and
     *       fails the equality. Give two arms the same credential and both resolve to the
     *       same script, so a seize would be authorised by presenting the transfer validator's
     *       withdrawal, and the third-party invariants would never run. Upstream documents
     *       this as the deployer's responsibility and deliberately does not enforce it.</li>
     *   <li><strong>Positive datum bound.</strong> Zero or negative forbids every inline datum
     *       on a holder-created PLB output, which is every transfer output — the transfer path
     *       stops working entirely.</li>
     * </ul>
     *
     * @throws IllegalStateException listing every violation, not just the first: a deployment
     *         is assembled once and a report that stops at the first problem costs another
     *         round trip for each subsequent one.
     */
    public void validateForDeployment() {
        List<String> problems = new ArrayList<>();

        Map<String, Credential> creds = new LinkedHashMap<>();
        creds.put("prog_logic_cred", progLogicCred);
        creds.put("transfer_cred", transferCred);
        creds.put("third_party_cred", thirdPartyCred);
        creds.put("unfracking_cred", unfrackingCred);
        creds.put("upgrade_cred", upgradeCred);

        creds.forEach((name, cred) -> {
            if (cred == null || cred.getBytes() == null || cred.getBytes().length != 28) {
                int len = (cred == null || cred.getBytes() == null) ? -1 : cred.getBytes().length;
                problems.add(name + " must be a 28-byte hash, got " + (len < 0 ? "null" : len + " bytes")
                        + " — a reward account can only ever hold 28 bytes, so this credential could "
                        + "never be satisfied by any transaction");
            }
        });

        var delegates = new LinkedHashMap<String, Credential>();
        delegates.put("transfer_cred", transferCred);
        delegates.put("third_party_cred", thirdPartyCred);
        delegates.put("unfracking_cred", unfrackingCred);
        var names = new ArrayList<>(delegates.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                Credential a = delegates.get(names.get(i));
                Credential b = delegates.get(names.get(j));
                if (a != null && b != null && PlutusCredentialCodec.hex(a).equals(PlutusCredentialCodec.hex(b))
                        && a.getType() == b.getType()) {
                    problems.add(names.get(i) + " and " + names.get(j) + " are the same credential ("
                            + PlutusCredentialCodec.hex(a) + ") — programmable_logic_base's dispatch "
                            + "collapses, and one delegate's invariants can be satisfied by presenting "
                            + "another's withdrawal");
                }
            }
        }

        if (maxInlineDatumBytes <= 0) {
            problems.add("max_inline_datum_bytes must be positive, got " + maxInlineDatumBytes
                    + " — a non-positive bound forbids every inline datum on a holder-created "
                    + "programmable output, which disables transfers");
        }

        if (registryNodePolicyId == null || registryNodePolicyId.length() != 56) {
            problems.add("registry_node_cs must be a 28-byte policy id in hex (56 chars), got "
                    + registryNodePolicyId);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "refusing to deploy an unsound protocol-params datum. protocol_params_mint only "
                            + "shape-checks this datum at mint time, so nothing on chain would stop it:\n  - "
                            + String.join("\n  - ", problems));
        }
    }

    /** Hex of a credential's raw hash, for storage and display. */
    public String progLogicCredHex() {
        return PlutusCredentialCodec.hex(progLogicCred);
    }

    public String transferCredHex() {
        return PlutusCredentialCodec.hex(transferCred);
    }

    public String thirdPartyCredHex() {
        return PlutusCredentialCodec.hex(thirdPartyCred);
    }

    public String unfrackingCredHex() {
        return PlutusCredentialCodec.hex(unfrackingCred);
    }

    public String upgradeCredHex() {
        return PlutusCredentialCodec.hex(upgradeCred);
    }
}
