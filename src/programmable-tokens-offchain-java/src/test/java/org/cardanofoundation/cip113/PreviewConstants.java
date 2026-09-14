package org.cardanofoundation.cip113;

/**
 * Credentials for tests that submit real transactions.
 *
 * <p>Every value here is sourced from the environment and none has a fallback. That is
 * deliberate and is the point of this class: a hardcoded 24-word mnemonic sat in this file
 * for months with the {@code getenv} line commented out above it, which meant signing keys
 * were never the missing piece — a full {@code ./gradlew test} only failed to put
 * transactions on chain if the Blockfrost key happened to be absent. A placeholder fallback
 * is the same defect wearing a different value, so there is none.
 *
 * <p>Offline tests must NOT use these. Deriving an address to build a transaction that is
 * never submitted needs a deterministic mnemonic, not a funded one — see
 * {@code BootstrapFixture.OFFLINE_DERIVATION_MNEMONIC}.
 */
public interface PreviewConstants {

    /** The wallet every submitting test signs with. No default: absent means fail, loudly. */
    String ADMIN_MNEMONIC = requireEnv("WALLET_MNEMONIC");

    String BLOCKFROST_KEY_PREVIEW = System.getenv("BLOCKFROST_KEY_PREVIEW");

    String BLOCKFROST_KEY_PREPROD = System.getenv("BLOCKFROST_KEY_PREPROD");

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set, and it has no default.\n"
                    + "Tests that submit transactions sign with a real wallet, so this value is "
                    + "never defaulted or placeheld -- a fallback is what previously let the suite "
                    + "arm itself on any machine that cloned it.\n"
                    + "Export " + name + " for the wallet you intend to spend from, on the network "
                    + "you intend to spend on. Offline tests do not need it and must not read it.");
        }
        return value;
    }
}
