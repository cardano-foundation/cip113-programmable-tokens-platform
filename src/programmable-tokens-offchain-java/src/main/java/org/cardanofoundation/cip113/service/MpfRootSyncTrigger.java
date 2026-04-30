package org.cardanofoundation.cip113.service;

// Hook called after any MPF trie mutation commits.
// Sub-plan 05 provides the concrete implementation.
public interface MpfRootSyncTrigger {
    void onRootChanged(String policyId);
}
