package org.cardanofoundation.cip113.model.bootstrap;

public record ProtocolBootstrapParams(ProtocolParams protocolParams,
                                      CoordinationParams coordinationParams,
                                      ProgrammableLogicGlobalParams programmableLogicGlobalPrams,
                                      ProgrammableLogicBaseParams programmableLogicBaseParams,
                                      UnfrackingParams unfrackingParams,
                                      UpgradeMultisigParams upgradeMultisigParams,
                                      IssuanceParams issuanceParams,
                                      DirectoryMintParams directoryMintParams,
                                      DirectorySpendParams directorySpendParams,
                                      TxInput programmableBaseRefInput,
                                      TxInput programmableGlobalRefInput,
                                      TxInput unfrackingRefInput,
                                      String txHash) {

}
