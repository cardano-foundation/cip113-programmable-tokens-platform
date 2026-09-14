package org.cardanofoundation.cip113;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.blueprint.Validator;

import java.util.List;

import static org.cardanofoundation.cip113.PreviewConstants.BLOCKFROST_KEY_PREVIEW;

@Slf4j
public abstract class AbstractPreviewTest {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    /**
     * The backend every test in this hierarchy talks to. No default, deliberately.
     *
     * <p>This used to fall back to real Blockfrost preview, which meant a test that forgot to
     * say where it was pointing still pointed somewhere real. Combined with a mnemonic that
     * used to be hardcoded, "forgot to configure" and "submitted to a public testnet" were the
     * same state. A placeholder default would be the same defect wearing a different value, so
     * there is none: say where you are pointing, or nothing runs.
     *
     * <p>Every subclass is gated on this same variable being set (see
     * SubmittingTestsAreGatedTest), so in normal use an unset value disables the test rather
     * than reaching this exception. The throw is the backstop for a subclass that is added
     * later without a gate.
     */
    protected static final String BACKEND_URL = requireBackendUrl();

    static String requireBackendUrlForPreprod() { return requireBackendUrl(); }

    private static String requireBackendUrl() {
        String url = System.getenv("CARDANO_BACKEND_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("CARDANO_BACKEND_URL is not set, and it has no default.\n"
                    + "Tests in this hierarchy read from, and may submit to, whatever this names. It "
                    + "previously defaulted to real Blockfrost preview, so an unconfigured run was a "
                    + "run against a public testnet.\n"
                    + "Set it explicitly -- a local Yaci devnet, or a public endpoint you intend to "
                    + "use -- and set CARDANO_NETWORK_MAGIC to match.");
        }
        return url;
    }

    /**
     * Unlike the URL, this KEEPS a placeholder, and the asymmetry is deliberate: a local Yaci
     * devnet ignores the key entirely, so requiring a real one would block the only backend a
     * contributor can safely run against. The placeholder is harmless now that the URL must be
     * named explicitly -- it can no longer be the last accidental thing standing between a run
     * and a public network.
     */
    protected static final String BACKEND_KEY = System.getenv().getOrDefault("CARDANO_BACKEND_KEY",
            BLOCKFROST_KEY_PREVIEW == null ? "dummy" : BLOCKFROST_KEY_PREVIEW);

    protected static final Network network = System.getenv("CARDANO_NETWORK_MAGIC") == null
            ? Networks.preview()
            : new Network(0b0000, Long.parseLong(System.getenv("CARDANO_NETWORK_MAGIC")));

    protected static final Account adminAccount = Account.createFromMnemonic(network, PreviewConstants.ADMIN_MNEMONIC);

    protected static final Account refInputAccount = Account.createFromMnemonic(network, PreviewConstants.ADMIN_MNEMONIC, 10, 0);

    protected static final Account aliceAccount = Account.createFromMnemonic(network, PreviewConstants.ADMIN_MNEMONIC, 1, 0);

    protected static final Account bobAccount = Account.createFromMnemonic(network, PreviewConstants.ADMIN_MNEMONIC, 2, 0);

    protected static final Account userWipeAccount = Account.createFromMnemonic(network, PreviewConstants.ADMIN_MNEMONIC, 3, 0);

    static {
        log.info("Admin Address: {}", adminAccount.baseAddress());
        log.info("Alice Address: {}", aliceAccount.baseAddress());
        log.info("Bob Address: {}", bobAccount.baseAddress());
        log.info("Wipe Address: {}", userWipeAccount.baseAddress());
    }

    protected final BFBackendService bfBackendService = new BFBackendService(BACKEND_URL, BACKEND_KEY);

    protected final QuickTxBuilder quickTxBuilder = new QuickTxBuilder(bfBackendService);

    protected String getCompiledCodeFor(String contractTitle, List<Validator> validators) {
        return validators.stream().filter(validator -> validator.title().equals(contractTitle)).findAny().get().compiledCode();
    }

}
