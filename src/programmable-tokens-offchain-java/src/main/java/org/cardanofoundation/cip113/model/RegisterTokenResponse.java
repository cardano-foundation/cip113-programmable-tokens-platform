package org.cardanofoundation.cip113.model;

public record RegisterTokenResponse(String policyId,
                                    String unsignedCborTx) {

}
