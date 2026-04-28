package org.cardanofoundation.cip113.model.keri;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionResponse {

    private final boolean exists;
    private final Boolean hasCredential;
    private final Boolean hasCardanoAddress;
    private final Map<String, Object> attributes;
    private final Integer credentialRole;
    private final String credentialRoleName;
    private final String cardanoAddress;
    private final String kycProofPayload;
    private final String kycProofSignature;
    private final String kycProofEntityVkey;
    private final Long kycProofValidUntil;
}
