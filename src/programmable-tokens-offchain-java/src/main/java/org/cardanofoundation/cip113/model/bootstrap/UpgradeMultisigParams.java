package org.cardanofoundation.cip113.model.bootstrap;

import java.util.List;

/** Trampoline-2 upgrade authority named by the coordination datum's upgrade_logic_cred. */
public record UpgradeMultisigParams(List<String> signers, int threshold, String scriptHash, String rewardAddress) {
}
