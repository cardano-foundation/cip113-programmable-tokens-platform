package org.cardanofoundation.cip113.config;

import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Time ↔ slot conversion on a devnet.
 *
 * <p>The backend would not start under the {@code devnet} profile at all:
 * {@code ClasspathConversionsFactory} looks the era history up by network from data bundled
 * in the conversions library, which ships genesis for mainnet, preprod, preview and
 * sanchonet only, and throws {@code Unsupported network type: DEV} for anything else. It
 * could not have shipped a devnet's: a devnet's genesis is created when the devnet is.
 *
 * <p>So the devnet path is built from the genesis files the node is actually running, and
 * these tests pin the two things that makes load-bearing — that it constructs at all, and
 * that the arithmetic it produces is the trivially correct one for a chain with a single era
 * running from its own genesis.
 *
 * <p>Why the arithmetic is worth asserting rather than assuming: a wrong {@code systemStart}
 * or slot length does not fail loudly. It yields a slot number, the transaction is built,
 * the scripts evaluate perfectly — script evaluation never translates time — and the ledger
 * rejects it at submit with {@code TimeTranslationPastHorizon}, after the user has signed.
 */
class DevnetConvertersTest {

    private static final String BYRON = "classpath:/devkit/byron-genesis.json";
    private static final String SHELLEY = "classpath:/devkit/shelley-genesis.json";

    private static org.cardanofoundation.conversions.CardanoConverters devnet() {
        return new AppConfig().cardanoConverters("devnet", BYRON, SHELLEY, new DefaultResourceLoader());
    }

    @Test
    @DisplayName("devnet converters build from genesis files instead of failing on NetworkType.DEV")
    void devnetConvertersBuild() {
        var converters = devnet();

        assertEquals(NetworkType.DEV, converters.conversionsConfig().networkType());
        // Read from the genesis file, not from a constant: this is what makes the bean specific
        // to the devnet actually running.
        assertEquals(42L, converters.genesisConfig().getProtocolNetworkMagic());
        assertEquals(java.time.Duration.ofSeconds(1), converters.genesisConfig().getShelleySlotLength());
    }

    /**
     * A single era running from genesis reduces the library's Byron/Shelley arithmetic to
     * {@code slot = (t - systemStart) / slotLength}. Asserted at the boundary (offset 0) and
     * beyond it, because an off-by-one-era history shows up as a constant offset rather than
     * as an error.
     */
    @Test
    @DisplayName("slot number is seconds since the genesis system start")
    void slotIsSecondsSinceSystemStart() {
        var converters = devnet();
        var systemStart = converters.genesisConfig().getShelleyStartTime();

        for (long seconds : new long[]{0L, 1L, 60L, 900L, 86_400L}) {
            assertEquals(seconds, converters.time().toSlot(systemStart.plusSeconds(seconds)),
                    "slot at systemStart + " + seconds + "s");
        }
    }

    @Test
    @DisplayName("slot and time round-trip")
    void roundTrips() {
        var converters = devnet();
        var systemStart = converters.genesisConfig().getShelleyStartTime();

        var t = systemStart.plusSeconds(12_345);
        assertEquals(t, converters.slot().slotToTime(converters.time().toSlot(t)));
    }

    /**
     * The TTL every rwa-token and KYC path sets is {@code now + 15 minutes} translated through
     * this bean. On a chain whose genesis is minutes old that must land just past the tip, not
     * tens of millions of slots beyond it — which is what a mainnet era history produces, and
     * what the node rejects as past-horizon.
     */
    @Test
    @DisplayName("a 15-minute TTL lands 900 slots ahead, not millions")
    void ttlIsPlausible() {
        var converters = devnet();
        var systemStart = converters.genesisConfig().getShelleyStartTime();

        long atStart = converters.time().toSlot(systemStart);
        long fifteenMinutesLater = converters.time().toSlot(systemStart.plusMinutes(15));

        assertEquals(900L, fifteenMinutesLater - atStart);
    }

    @Test
    @DisplayName("named networks still resolve through the library's bundled data")
    void namedNetworksUnaffected() {
        var loader = new DefaultResourceLoader();

        for (String network : new String[]{"preview", "preprod", "mainnet"}) {
            var converters = new AppConfig().cardanoConverters(network, BYRON, SHELLEY, loader);
            assertTrue(converters.genesisConfig().getProtocolNetworkMagic() > 0, network);
        }
    }

    /**
     * A devkit chain really has a short Byron era before Shelley (600 slots on the default
     * cluster). Modelling it as one era from slot 0 is exact for absolute slots only while
     * both eras share a slot length — so that is refused rather than assumed. Checked with a
     * genesis pair whose lengths differ: preview's Byron genesis (20s slots) against the
     * devkit's Shelley genesis (1s).
     */
    @Test
    @DisplayName("mismatched Byron and Shelley slot lengths are refused, not silently offset")
    void mismatchedSlotLengthsRefused() {
        var e = assertThrows(IllegalStateException.class, () -> new AppConfig().cardanoConverters(
                "devnet", "classpath:/genesis-files/preview/byron-genesis.json", SHELLEY,
                new DefaultResourceLoader()));

        assertTrue(e.getMessage().contains("slot lengths"), e.getMessage());
    }

    /**
     * {@code store.cardano.*-genesis-file} is yaci-store's property; this backend only borrows
     * it. yaci-store reads it with {@code new File(...)}, so a bare path must work and a
     * {@code file:} URL must not — a URL resolves fine through Spring here and then fails over
     * in yaci-store with a "not found" naming a path that plainly exists.
     */
    @Test
    @DisplayName("a bare filesystem path is accepted, the way yaci-store reads the same property")
    void bareFilesystemPathAccepted() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("devnet-genesis");
        for (String name : new String[]{"byron-genesis.json", "shelley-genesis.json"}) {
            try (var in = getClass().getResourceAsStream("/devkit/" + name)) {
                java.nio.file.Files.copy(in, dir.resolve(name));
            }
        }

        var converters = new AppConfig().cardanoConverters("devnet",
                dir.resolve("byron-genesis.json").toString(),
                dir.resolve("shelley-genesis.json").toString(),
                new DefaultResourceLoader());

        assertEquals(42L, converters.genesisConfig().getProtocolNetworkMagic());
    }

    @Test
    @DisplayName("a file: URL is refused, because yaci-store cannot read one")
    void fileUrlRefused() {
        var e = assertThrows(IllegalStateException.class, () -> new AppConfig().cardanoConverters(
                "devnet", "file:/tmp/byron-genesis.json", "file:/tmp/shelley-genesis.json",
                new DefaultResourceLoader()));

        assertTrue(e.getMessage().contains("bare filesystem path"), e.getMessage());
    }

    @Test
    @DisplayName("a genesis file that is not there fails at startup, naming both paths")
    void missingGenesisFailsLoudly() {
        var e = assertThrows(IllegalStateException.class, () -> new AppConfig().cardanoConverters(
                "devnet", "classpath:/devkit/nope.json", SHELLEY, new DefaultResourceLoader()));

        assertTrue(e.getMessage().contains("nope.json"), e.getMessage());
        assertTrue(e.getMessage().contains("DEVNET-GUIDE"), e.getMessage());
    }
}
