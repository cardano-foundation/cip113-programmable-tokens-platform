package org.cardanofoundation.cip113.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One GlobalState logic this platform built for a token, stored in
 * {@code rwa_token_registration.gs_logics} (JSON list).
 *
 * <p>Writes resolve the live logic by GlobalState datum field 0 through this registry; a hash
 * that is absent is refused (UNKNOWN LOGIC) rather than built against a guessed script.
 *
 * <p>{@code refState}: NONE (never published), PENDING (a publish tx was built, not yet seen on
 * chain), CONFIRMED (seen on chain). A build that finds the ref UTxO live promotes the entry to
 * CONFIRMED; a standalone write refuses a PENDING entry whose ref is not visible yet. Only the
 * registration chain spends against PENDING, because it carries its own publish tx in the same,
 * not yet submitted chain.
 * {@code registrationState} is PENDING or CONFIRMED in the same sense for the stake credential.
 *
 * <p>{@code installedTxHash} is the transaction that made this logic GlobalState's field 0: the
 * genesis tx for v1, the rotation tx for a later one. Null means "prepared, never installed" —
 * which is what LockUpgrades' "no rotation in flight" check looks for. A previously installed
 * logic stays marked after a rotation away from it, so it never reads as pending.
 */
public record GsLogicEntry(
        String logicHash,
        int maxDatumBytes,
        String scriptCborHex,
        String refTxHash,
        Integer refOutputIndex,
        String refState,
        String registrationTxHash,
        String registrationState,
        String installedTxHash,
        Long refTtlSlot,
        Long registrationTtlSlot) {

    /** Without TTLs (rows written before the builders record them). */
    public GsLogicEntry(String logicHash, int maxDatumBytes, String scriptCborHex, String refTxHash,
                        Integer refOutputIndex, String refState, String registrationTxHash,
                        String registrationState, String installedTxHash) {
        this(logicHash, maxDatumBytes, scriptCborHex, refTxHash, refOutputIndex, refState,
                registrationTxHash, registrationState, installedTxHash, null, null);
    }

    /** A PENDING tx (publish or registration) is abandoned once the tip passes its TTL. Unknown tip
     *  or TTL = still in flight. */
    public static boolean pendingExpired(Long ttlSlot, java.util.Optional<Long> tip) {
        return ttlSlot != null && tip.isPresent() && tip.get() > ttlSlot;
    }

    public GsLogicEntry withPendingRegistration(String txHash, long ttlSlot) {
        return new GsLogicEntry(logicHash, maxDatumBytes, scriptCborHex, refTxHash, refOutputIndex, refState,
                txHash, PENDING, installedTxHash, refTtlSlot, ttlSlot);
    }

    public static final String NONE = "NONE";
    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public boolean hasRef() {
        return refTxHash != null && refOutputIndex != null && !NONE.equals(refState);
    }

    public GsLogicEntry withRef(String txHash, int outputIndex, String state) {
        return new GsLogicEntry(logicHash, maxDatumBytes, scriptCborHex, txHash, outputIndex, state,
                registrationTxHash, registrationState, installedTxHash, refTtlSlot, registrationTtlSlot);
    }

    /** A newly built publish tx: PENDING until seen on chain, abandoned once {@code ttlSlot} passes. */
    public GsLogicEntry withPendingRef(String txHash, int outputIndex, long ttlSlot) {
        return new GsLogicEntry(logicHash, maxDatumBytes, scriptCborHex, txHash, outputIndex, PENDING,
                registrationTxHash, registrationState, installedTxHash, ttlSlot, registrationTtlSlot);
    }

    public GsLogicEntry withRefState(String state) {
        return new GsLogicEntry(logicHash, maxDatumBytes, scriptCborHex, refTxHash, refOutputIndex, state,
                registrationTxHash, registrationState, installedTxHash, refTtlSlot, registrationTtlSlot);
    }

    public GsLogicEntry withRegistration(String txHash, String state) {
        return new GsLogicEntry(logicHash, maxDatumBytes, scriptCborHex, refTxHash, refOutputIndex, refState,
                txHash, state, installedTxHash, refTtlSlot, registrationTtlSlot);
    }

    public GsLogicEntry withInstalled(String txHash) {
        return new GsLogicEntry(logicHash, maxDatumBytes, scriptCborHex, refTxHash, refOutputIndex, refState,
                registrationTxHash, registrationState, txHash, refTtlSlot, registrationTtlSlot);
    }

    public boolean everInstalled() {
        return installedTxHash != null;
    }

    public static List<GsLogicEntry> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return new ArrayList<>(MAPPER.readValue(json, new TypeReference<List<GsLogicEntry>>() {}));
        } catch (Exception e) {
            throw new IllegalStateException("corrupt gs_logics registry", e);
        }
    }

    public static String toJson(List<GsLogicEntry> entries) {
        try {
            return MAPPER.writeValueAsString(entries);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise gs_logics registry", e);
        }
    }

    public static Optional<GsLogicEntry> find(String json, String logicHash) {
        if (logicHash == null) return Optional.empty();
        return parseList(json).stream().filter(e -> logicHash.equalsIgnoreCase(e.logicHash())).findFirst();
    }

    /** Insert or replace the entry with the same hash, preserving order. */
    public static String upsert(String json, GsLogicEntry entry) {
        List<GsLogicEntry> list = parseList(json);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).logicHash().equalsIgnoreCase(entry.logicHash())) {
                list.set(i, entry);
                replaced = true;
            }
        }
        if (!replaced) list.add(entry);
        return toJson(list);
    }
}
