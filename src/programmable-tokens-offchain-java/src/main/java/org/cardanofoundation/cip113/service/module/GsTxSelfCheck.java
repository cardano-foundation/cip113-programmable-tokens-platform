package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plan Addendum I17 (user decision: every GlobalState transaction). Decodes the FINAL, balanced
 * transaction and checks it does exactly what the request asked — before the admin is handed it
 * to sign.
 *
 * <p>The validators check per-action field equality and the GlobalState value, but they do not
 * stop OTHER admin-authorised purposes riding along: a bundled registry upgrade, a power-users
 * insert granting {@code can_mint}, a stray certificate. Balancing, ordering hooks and library
 * upgrades can also shift what was built away from what was intended. This is the last gate.
 *
 * <p>Every failure is an {@link IllegalStateException} naming the clause; it is a platform bug,
 * never a user error, so builders surface it as a build failure.
 */
final class GsTxSelfCheck {
    private GsTxSelfCheck() {}

    /**
     * @param gsInput             the GlobalState UTxO this tx must spend
     * @param gsAddress           where the continuing GlobalState must sit (= the input's address)
     * @param gsPolicyId          GlobalState NFT policy
     * @param gsAssetNameHex      GlobalState NFT asset name
     * @param expectedDatum       the continuing datum, exactly
     * @param logicRedeemers      reward address → the full {@code GlobalStateSpendRedeemer} its
     *                            withdrawal must carry (one entry; two for a logic rotation)
     * @param otherWithdrawals    reward addresses that may ALSO withdraw (mint/burn protocol and
     *                            authority scripts); empty for admin actions
     * @param allowedMintPolicies policies that may mint/burn; empty for admin actions
     * @param allowedScriptInputs inputs besides GlobalState that may carry a Spend redeemer
     *                            (e.g. the burned token UTxO); empty for admin actions
     * @param expectedSigners     required signers, exactly (hex key hashes)
     */
    record Expectation(TransactionInput gsInput, String gsAddress, String gsPolicyId, String gsAssetNameHex,
                       PlutusData expectedDatum, Map<String, PlutusData> logicRedeemers,
                       Set<String> otherWithdrawals, Set<String> allowedMintPolicies,
                       Set<TransactionInput> allowedScriptInputs, Set<String> expectedSigners) {}

    static void verify(Transaction tx, Expectation e) {
        var body = tx.getBody();
        // 1. Exactly the GlobalState input.
        long gsSpends = body.getInputs().stream().filter(i -> same(i, e.gsInput())).count();
        if (gsSpends != 1) fail("the GlobalState input " + ref(e.gsInput()) + " is spent " + gsSpends + " times");

        // 2. Exactly one output carries the NFT: same address, NFT-only value, no ref script, exact datum.
        List<TransactionOutput> carrying = body.getOutputs().stream().filter(o -> holdsNft(o, e)).toList();
        if (carrying.size() != 1) fail(carrying.size() + " outputs carry the GlobalState NFT");
        TransactionOutput gs = carrying.getFirst();
        if (!e.gsAddress().equals(gs.getAddress())) fail("continuing GlobalState moved to " + gs.getAddress());
        if (gs.getScriptRef() != null) fail("continuing GlobalState carries a reference script");
        List<MultiAsset> assets = gs.getValue().getMultiAssets();
        if (assets == null || assets.size() != 1 || assets.getFirst().getAssets().size() != 1
                || !BigInteger.ONE.equals(assets.getFirst().getAssets().getFirst().getValue()))
            fail("continuing GlobalState holds more than its NFT");
        if (gs.getInlineDatum() == null || !hex(gs.getInlineDatum()).equals(hex(e.expectedDatum())))
            fail("continuing GlobalState datum is not the one this change computes");
        int gsOutputIndex = body.getOutputs().indexOf(gs);
        for (PlutusData redeemer : e.logicRedeemers().values()) {
            if (!(redeemer instanceof com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData c)
                    || c.getData().getPlutusDataList().isEmpty()
                    || !(c.getData().getPlutusDataList().getFirst() instanceof com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData idx)
                    || idx.getValue().intValue() != gsOutputIndex)
                fail("the logic redeemer's global_state_output_index is not the continuing GlobalState output ("
                        + gsOutputIndex + ")");
        }

        // 3. Withdrawals: every logic withdrawal present; nothing outside the allowed set.
        var withdrawals = body.getWithdrawals() == null ? List.<com.bloxbean.cardano.client.transaction.spec.Withdrawal>of()
                : body.getWithdrawals();
        Set<String> present = new HashSet<>();
        withdrawals.forEach(w -> present.add(w.getRewardAddress()));
        for (String logic : e.logicRedeemers().keySet())
            if (!present.contains(logic)) fail("the GlobalState logic withdrawal " + logic + " is missing");
        for (String w : present)
            if (!e.logicRedeemers().containsKey(w) && !e.otherWithdrawals().contains(w))
                fail("unexpected withdrawal " + w);

        // 4. Each logic withdrawal carries exactly its redeemer.
        List<Redeemer> redeemers = tx.getWitnessSet() == null || tx.getWitnessSet().getRedeemers() == null
                ? List.of() : tx.getWitnessSet().getRedeemers();
        for (var entry : e.logicRedeemers().entrySet()) {
            int idx = -1;
            for (int i = 0; i < withdrawals.size(); i++)
                if (withdrawals.get(i).getRewardAddress().equals(entry.getKey())) idx = i;
            final int widx = idx;
            var r = redeemers.stream().filter(x -> x.getTag() == RedeemerTag.Reward
                    && x.getIndex().intValue() == widx).findFirst();
            if (r.isEmpty() || !hex(r.get().getData()).equals(hex(entry.getValue())))
                fail("the GlobalState logic withdrawal " + entry.getKey() + " does not carry the intended action");
        }

        // 5. No certificates, no unexpected mint.
        if (body.getCerts() != null && !body.getCerts().isEmpty()) fail("carries " + body.getCerts().size() + " certificate(s)");
        if (body.getMint() != null)
            for (MultiAsset m : body.getMint())
                if (!e.allowedMintPolicies().contains(m.getPolicyId())) fail("unexpected mint under " + m.getPolicyId());

        // 6. Script inputs: only GlobalState and the explicitly allowed ones carry a Spend redeemer.
        List<TransactionInput> sorted = new ArrayList<>(body.getInputs());
        sorted.sort(Comparator.comparing(TransactionInput::getTransactionId).thenComparingInt(TransactionInput::getIndex));
        for (Redeemer r : redeemers) {
            if (r.getTag() != RedeemerTag.Spend) continue;
            TransactionInput in = sorted.get(r.getIndex().intValue());
            if (!same(in, e.gsInput()) && e.allowedScriptInputs().stream().noneMatch(a -> same(a, in)))
                fail("unexpected script input " + ref(in));
        }

        // 7. Required signers, exactly.
        Set<String> signers = new HashSet<>();
        if (body.getRequiredSigners() != null)
            body.getRequiredSigners().forEach(b -> signers.add(HexUtil.encodeHexString(b).toLowerCase(Locale.ROOT)));
        Set<String> expected = new HashSet<>();
        e.expectedSigners().forEach(s -> expected.add(s.toLowerCase(Locale.ROOT)));
        if (!signers.equals(expected)) fail("required signers " + signers + " differ from " + expected);
    }

    private static boolean holdsNft(TransactionOutput o, Expectation e) {
        if (o.getValue() == null || o.getValue().getMultiAssets() == null) return false;
        return o.getValue().getMultiAssets().stream().anyMatch(m -> m.getPolicyId().equals(e.gsPolicyId())
                && m.getAssets().stream().anyMatch(a -> {
                    String name = a.getName().startsWith("0x") ? a.getName().substring(2) : a.getName();
                    return name.equalsIgnoreCase(e.gsAssetNameHex());
                }));
    }

    private static boolean same(TransactionInput a, TransactionInput b) {
        return a.getTransactionId().equalsIgnoreCase(b.getTransactionId()) && a.getIndex() == b.getIndex();
    }

    private static String ref(TransactionInput i) {
        return i.getTransactionId() + "#" + i.getIndex();
    }

    private static String hex(PlutusData d) {
        return d.serializeToHex();
    }

    private static void fail(String clause) {
        throw new IllegalStateException("GlobalState self-check failed: " + clause);
    }
}
