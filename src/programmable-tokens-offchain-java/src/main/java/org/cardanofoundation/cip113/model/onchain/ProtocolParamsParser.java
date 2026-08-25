package org.cardanofoundation.cip113.model.onchain;

import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.core.CoreProtocolParamsDatum;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decodes the protocol-params NFT's inline datum for the chain indexer.
 *
 * <p>The decoding itself lives in {@link CoreProtocolParamsDatum}, which is where the core
 * contract's datum shapes belong. What is left here is the indexer's error policy: a datum
 * that will not decode must not stop the indexer, because the event stream carries whatever
 * happens to be on chain and one malformed UTxO cannot be allowed to halt ingestion. So this
 * returns {@link Optional#empty()} and logs, where {@code CoreProtocolParamsDatum} throws.
 *
 * <p>Previously this class did the decoding itself, by serialising the datum to JSON and
 * walking it by path — reading two of the five fields into a two-field record and silently
 * discarding the other three, which happen to be every delegate credential the protocol's
 * in-place upgrade mechanism exists to move.
 *
 * <p>The failure is logged at WARN with the offending datum, not swallowed: a params UTxO
 * that does not decode means either a datum this build does not understand (the core was
 * upgraded and this backend was not) or a genuinely malformed one, and both are worth
 * seeing in a log.
 */
@Component
@Slf4j
public class ProtocolParamsParser {

    public Optional<CoreProtocolParamsDatum> parse(String inlineDatum) {
        try {
            return Optional.of(CoreProtocolParamsDatum.fromHex(inlineDatum));
        } catch (RuntimeException e) {
            log.warn("could not decode protocol-params datum, skipping: {}", inlineDatum, e);
            return Optional.empty();
        }
    }
}
