package org.cardanofoundation.cip113.config;

import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.ConversionsConfig;
import org.cardanofoundation.conversions.EraHistory;
import org.cardanofoundation.conversions.GenesisConfig;
import org.cardanofoundation.conversions.converters.EpochConversions;
import org.cardanofoundation.conversions.converters.EraConversions;
import org.cardanofoundation.conversions.converters.SlotConversions;
import org.cardanofoundation.conversions.converters.TimeConversions;
import org.cardanofoundation.conversions.domain.Consensus;
import org.cardanofoundation.conversions.domain.Era;
import org.cardanofoundation.conversions.domain.EraHistoryItem;
import org.cardanofoundation.conversions.domain.EraType;
import org.cardanofoundation.conversions.domain.GenesisPaths;
import org.cardanofoundation.conversions.domain.LedgerProtocol;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.cardanofoundation.conversions.domain.Phase;
import org.cardanofoundation.conversions.domain.ProtocolVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableScheduling
@Slf4j
public class AppConfig {

    @Component
    @Getter
    public static class ProtocolParamsConfig {

        @Value("${network}")
        private String network;

        private List<String> transactionIds;

        @PostConstruct
        public void init() {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                String fileName = "protocol-params-" + network + ".json";
                ClassPathResource resource = new ClassPathResource(fileName);

                if (!resource.exists()) {
                    log.warn("Protocol params file not found: {}, using empty transaction list", fileName);
                    transactionIds = new ArrayList<>();
                    return;
                }

                JsonNode rootNode = objectMapper.readTree(resource.getInputStream());

                transactionIds = new ArrayList<>();
                JsonNode txIdsNode = rootNode.get("transaction_ids");
                if (txIdsNode != null && txIdsNode.isArray()) {
                    txIdsNode.forEach(node -> transactionIds.add(node.asText()));
                }

                log.info("Loaded {} transaction IDs from {} for network: {}", transactionIds.size(), fileName, network);
            } catch (IOException e) {
                log.error("Failed to load protocol-params-{}.json", network, e);
                transactionIds = new ArrayList<>();
            }
        }

    }

    @Component
    @Getter
    public static class Network {

        private final String network;

        public Network(@Value("${network}") String network) {
            this.network = network;
        }

        public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
            return switch (network) {
                case "preprod" -> Networks.preprod();
                case "preview" -> Networks.preview();
                // Local Yaci DevKit devnet: network id 0 (shared by every testnet-flavoured
                // network — see Networks.preprod()/preview()), magic 42 is this project's
                // established devnet convention (see docs/DEPLOYMENT.md and the deployment
                // records under protocol-bootstraps-devnet.json). Without this case "devnet"
                // silently fell through to Networks.mainnet(), so every address derived from the
                // devnet bootstrap (protocol-bootstraps-devnet.json) would be mainnet-shaped
                // while the actual on-chain UTxOs are testnet-shaped addr_test1.../stake_test1...
                // — the same "valid-looking hash, wrong address" failure mode WP-2 fixed, just at
                // the network-tag layer instead of the script-parameter layer.
                case "devnet" -> new com.bloxbean.cardano.client.common.model.Network(0b0000, 42);
                default -> Networks.mainnet();
            };
        }

    }

    /**
     * Time&nbsp;&harr;&nbsp;slot converters for the configured network.
     *
     * <p><b>{@code devnet} must map to {@link NetworkType#DEV}, not fall through to
     * MAINNET.</b> These converters are what {@code validTo} is computed from, and every
     * rwa-token path now sets a validity bound (the KYC membership proof only verifies
     * against a Finite upper bound). Translating "now + N" with MAINNET's era history on a
     * devnet whose genesis is minutes old yields a slot far beyond the node's known horizon,
     * and the ledger rejects the transaction at submit with
     * {@code TimeTranslationPastHorizon} — after the user has signed.
     *
     * <p>Nothing offline catches this: script evaluation does not translate time, so the
     * validators score perfectly and the transaction still cannot be submitted. It was found
     * by running the registration against a live devnet, where every path that carries a TTL
     * failed. {@code NetworkType.DEV} carries protocol magic 42, which is Yaci DevKit's.
     */
    @Bean
    public CardanoConverters cardanoConverters(
            @Value("${network}") String network,
            @Value("${store.cardano.byron-genesis-file:classpath:/devkit/byron-genesis.json}") String byronGenesis,
            @Value("${store.cardano.shelley-genesis-file:classpath:/devkit/shelley-genesis.json}") String shelleyGenesis,
            ResourceLoader resourceLoader) {
        var networkType = switch (network) {
            case "preprod" -> NetworkType.PREPROD;
            case "preview" -> NetworkType.PREVIEW;
            case "devnet", "yaci", "dev" -> NetworkType.DEV;
            default -> NetworkType.MAINNET;
        };
        log.info("INIT Converters network: {}, network type: {}", network, networkType);

        if (networkType == NetworkType.DEV) {
            return devnetConverters(byronGenesis, shelleyGenesis, resourceLoader);
        }
        return ClasspathConversionsFactory.createConverters(networkType);
    }

    /**
     * Converters for a devnet, built from the genesis files the node is actually running.
     *
     * <p>{@link ClasspathConversionsFactory} cannot do this: it looks the era history up by
     * network from data bundled in the library, which ships genesis for mainnet, preprod,
     * preview and sanchonet only — and throws {@code Unsupported network type: DEV} for
     * anything else. It could not be otherwise. A devnet's genesis is created when the devnet
     * is, so no library can have shipped it, and the one number that matters most here —
     * {@code systemStart} — is different for every devnet anyone starts.
     *
     * <p>Hence the genesis files come from configuration. They default to the bundled
     * {@code devkit/} snapshot, which is only correct if the devnet was started from those
     * same files; point {@code store.cardano.byron-genesis-file} and
     * {@code shelley-genesis-file} at the running node's genesis otherwise. Getting this
     * wrong is not subtle in its effect but is silent in its symptom: a {@code systemStart}
     * off by a year translates "now + 15 minutes" into a slot tens of millions beyond the
     * node's horizon, and the ledger rejects the transaction at submit with
     * {@code TimeTranslationPastHorizon} — after the user has signed. That is the same
     * failure the wrong-network case produced, from a different cause.
     *
     * <p>The era history has to be synthesised. {@code StaticEraHistoryFactory} also switches
     * on network type and hands back an EMPTY history for DEV, which is worse than throwing:
     * {@code GenesisConfig.firstShelleyEpochNo()} resolves it with {@code orElseThrow}, so the
     * failure would surface not at startup but at the first transaction that needs a TTL. A
     * devkit chain is Shelley-onwards from its own genesis with no real Byron period, so one
     * era item starting at epoch 0 / slot 0 describes it exactly, and the library's arithmetic
     * then reduces to {@code slot = (t - systemStart) / slotLength} — verified in
     * {@code DevnetConvertersTest}.
     *
     * <p><b>Limitation.</b> Epoch-based conversions are NOT right here: {@code slotsPerEpoch}
     * comes from a constant on {@code NetworkType.DEV} (432 000) rather than from the genesis
     * file, where a devkit epoch is typically 500 slots. Nothing in this backend uses epoch
     * conversions — only {@code time().toSlot(...)} and {@code slot().slotToTime(...)}, both
     * of which read slot length and start time only — but anything that starts to should not
     * assume this bean is correct for it.
     */
    private static CardanoConverters devnetConverters(String byronGenesis,
                                                      String shelleyGenesis,
                                                      ResourceLoader resourceLoader) {
        try {
            var byronUrl = resolveGenesis(byronGenesis, resourceLoader);
            var shelleyUrl = resolveGenesis(shelleyGenesis, resourceLoader);
            log.info("INIT Devnet converters from genesis: byron={}, shelley={}", byronUrl, shelleyUrl);

            var genesisPaths = new GenesisPaths(NetworkType.DEV, byronUrl, shelleyUrl);
            var conversionsConfig = new ConversionsConfig(NetworkType.DEV, genesisPaths);

            // One era, running from genesis. See the javadoc above for why this is synthesised
            // rather than derived.
            var eraHistory = new EraHistory(List.of(new EraHistoryItem(
                    Phase.Shelley,
                    Era.noGenesis(EraType.Shelley),
                    0L, Optional.empty(),   // first real slot
                    0L, Optional.empty(),   // first theoretical slot
                    0, Optional.empty(),    // start epoch
                    ProtocolVersion.VER_9_1,
                    Optional.of(LedgerProtocol.Praos),
                    Consensus.Ouroboros_Praos,
                    true)));

            var objectMapper = new ObjectMapper();
            requireUniformSlotLength(byronUrl, shelleyUrl, objectMapper);

            var genesisConfig = new GenesisConfig(conversionsConfig, eraHistory, objectMapper);
            var slotConversions = new SlotConversions(genesisConfig);

            log.info("INIT Devnet genesis: systemStart={}, slotLength={}, networkMagic={}",
                    genesisConfig.getShelleyStartTime(),
                    genesisConfig.getShelleySlotLength(),
                    genesisConfig.getProtocolNetworkMagic());

            // The most likely way to get this wrong is to leave the bundled devkit/ snapshot in
            // place: it is a genesis captured once and committed, so its systemStart is fixed in
            // the past while a devnet you just started begins now. Every slot would then be
            // computed from the wrong origin — off by however long ago the snapshot was taken —
            // and the ledger would reject the transactions at submit, blaming the time
            // translation rather than the file. A devnet genuinely running for weeks is possible,
            // so this warns rather than refuses.
            var genesisAge = java.time.Duration.between(
                    genesisConfig.getShelleyStartTime(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            if (genesisAge.toDays() > 7) {
                log.warn("Devnet genesis systemStart is {} days old ({}). If this devnet was started "
                                + "recently, these are the wrong genesis files — most likely the bundled "
                                + "devkit/ snapshot rather than the running node's. Every slot this "
                                + "backend computes would be offset by that much, and transactions would "
                                + "be rejected at submit as past-horizon. See docs/DEVNET-GUIDE.md.",
                        genesisAge.toDays(), genesisConfig.getShelleyStartTime());
            }

            return new CardanoConverters(
                    conversionsConfig,
                    genesisConfig,
                    new EpochConversions(genesisConfig, slotConversions),
                    slotConversions,
                    new TimeConversions(genesisConfig, slotConversions),
                    new EraConversions(genesisConfig, slotConversions));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not read the devnet genesis files (byron=" + byronGenesis
                            + ", shelley=" + shelleyGenesis + "). A devnet has no genesis anyone "
                            + "could have bundled, so these must point at the ones the node is "
                            + "running — see docs/DEVNET-GUIDE.md.", e);
        }
    }

    /**
     * Resolve a genesis location the same way yaci-store does, because it is the same property.
     *
     * <p>{@code store.cardano.*-genesis-file} is yaci-store's own configuration key; this
     * backend piggybacks on it so a devnet is described once rather than twice. That makes
     * yaci-store's parser the authority on the format, and it accepts exactly two forms: a
     * {@code classpath:} location, or a bare filesystem path it hands to {@code new File(...)}.
     *
     * <p>A {@code file:} URL is therefore rejected — deliberately, and early. Spring's
     * {@code ResourceLoader} would happily resolve one, so the converters here would work while
     * yaci-store failed on the same value with "Shelley genesis file not found at path", quoting
     * a path that plainly exists (and, in its 0.1.6 message, naming the wrong one of the two
     * files). Failing here instead says what to do about it.
     */
    private static java.net.URL resolveGenesis(String location, ResourceLoader resourceLoader) throws IOException {
        if (location.startsWith("classpath:")) {
            return resourceLoader.getResource(location).getURL();
        }
        if (location.startsWith("file:")) {
            throw new IllegalStateException(
                    "genesis location '" + location + "' is a file: URL. Use a bare filesystem path "
                            + "(/path/to/shelley-genesis.json) or a classpath: location instead — this "
                            + "is yaci-store's own property and it reads the value with new File(...), "
                            + "so a URL fails there even though it resolves here.");
        }
        return new java.io.File(location).toURI().toURL();
    }

    /**
     * The one assumption the single-era model rests on, checked rather than documented.
     *
     * <p>A devkit chain is not literally single-era: its genesis gives Byron a short run —
     * one epoch, 600 slots on the default cluster — before Shelley begins. Modelling it as
     * "Shelley from slot 0" is nevertheless exact for absolute slot numbers, because the two
     * eras are contiguous and use the SAME slot length, so
     * {@code slot = (t - byronStart) / slotLength} whichever era {@code t} falls in. Verified
     * against a live devnet: a chain 3 902 seconds old reported slot 3 902 both ways —
     * 600 Byron slots plus 3 302 Shelley slots.
     *
     * <p>Change the slot lengths so they differ and that identity breaks, silently: every slot
     * this bean produces would be off by a fixed amount, transactions would build, scripts
     * would evaluate, and the ledger would reject them at submit. Nothing downstream could
     * attribute that to a genesis file. So it is refused here, where the two numbers are in
     * hand and the message can name them.
     */
    private static void requireUniformSlotLength(java.net.URL byronUrl,
                                                 java.net.URL shelleyUrl,
                                                 ObjectMapper objectMapper) throws IOException {
        var byronSlotDurationMs = objectMapper.readTree(byronUrl)
                .path("blockVersionData").path("slotDuration").asLong(0);
        var shelleySlotLengthSeconds = objectMapper.readTree(shelleyUrl)
                .path("slotLength").asDouble(0);

        if (byronSlotDurationMs <= 0 || shelleySlotLengthSeconds <= 0) {
            throw new IllegalStateException(
                    "devnet genesis does not declare slot lengths (byron blockVersionData.slotDuration="
                            + byronSlotDurationMs + "ms, shelley slotLength=" + shelleySlotLengthSeconds
                            + "s). These files do not look like a Cardano node's genesis.");
        }

        long shelleySlotLengthMs = Math.round(shelleySlotLengthSeconds * 1000d);
        if (byronSlotDurationMs != shelleySlotLengthMs) {
            throw new IllegalStateException(
                    "devnet genesis has different Byron and Shelley slot lengths (" + byronSlotDurationMs
                            + "ms vs " + shelleySlotLengthMs + "ms). This backend models a devnet as a "
                            + "single era running from genesis, which is only equivalent to the real "
                            + "two-era chain while both slot lengths agree. With different lengths every "
                            + "slot computed here would be silently offset, and the ledger would reject "
                            + "the resulting transactions at submit. See AppConfig#devnetConverters.");
        }
    }

}
