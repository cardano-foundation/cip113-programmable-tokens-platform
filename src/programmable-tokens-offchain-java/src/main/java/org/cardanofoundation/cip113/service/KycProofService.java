package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.Role;
import org.cardanofoundation.cip113.model.keri.KycProofResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

@Service
@Slf4j
public class KycProofService {

    private final String signingMnemonic;
    private final int validityDays;
    private final String network;

    public KycProofService(
            @Value("${keri.signing-mnemonic}") String signingMnemonic,
            @Value("${keri.kyc-proof-validity-days:30}") int validityDays,
            @Value("${network:preview}") String network) {
        this.signingMnemonic = signingMnemonic;
        this.validityDays = validityDays;
        this.network = network;
    }

    /**
     * Generates a signed KYC proof for the given user address and role.
     * Payload is 37 bytes: user_pkh(28) || role(1) || valid_until(8 big-endian POSIX ms).
     */
    public KycProofResponse generateProof(String userAddress, int roleValue) {
        var networkInfo = switch (network) {
            case "mainnet" -> Networks.mainnet();
            case "preprod" -> Networks.preprod();
            default -> Networks.preview();
        };

        Account entityAccount = Account.createFromMnemonic(networkInfo, signingMnemonic);
        byte[] entityVkeyRaw = entityAccount.publicKeyBytes();
        String entityVkeyHex = HexUtil.encodeHexString(entityVkeyRaw);

        Address addr = new Address(userAddress);
        byte[] userPkhBytes = addr.getDelegationCredentialHash()
                .orElseThrow(() -> new IllegalStateException("Cannot extract delegation credential from address: " + userAddress));

        long validUntilMs = System.currentTimeMillis() + (validityDays * 86_400L * 1_000L);

        byte[] payload = new byte[37];
        System.arraycopy(userPkhBytes, 0, payload, 0, 28);
        payload[28] = (byte) roleValue;
        ByteBuffer.wrap(payload, 29, 8).putLong(validUntilMs);

        byte[] signature = new EdDSASigningProvider()
                .signExtended(payload, entityAccount.hdKeyPair().getPrivateKey().getKeyData());

        Role role = Role.fromValue(roleValue);

        log.info("Generated KYC proof: entityVkey={} userPkh={} role={} validUntil={}",
                entityVkeyHex.substring(0, 16) + "...",
                HexUtil.encodeHexString(userPkhBytes),
                role.name(),
                validUntilMs);

        return new KycProofResponse(
                HexUtil.encodeHexString(payload),
                HexUtil.encodeHexString(signature),
                entityVkeyHex,
                validUntilMs,
                roleValue,
                role.name()
        );
    }
}
