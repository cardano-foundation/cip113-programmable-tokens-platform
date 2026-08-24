/**
 * RWA-Token Substandard Flow
 *
 * Adds an optional receiver-KYC toggle, an on-chain denylist, and role-gated
 * "power users" on top of the kyc-extended pattern. The wizard reuses every
 * step component from basic kyc — the registration shape extends the
 * kyc-extended one with the rwa-token-specific policy ids and the
 * requires_receiver_kyc flag.
 */

import { registerFlow, isFlowEnabled } from '../flow-registry';
import type {
  RegistrationFlow,
  WizardState,
  RwaTokenRegistrationData,
  StepComponentProps,
} from '@/types/registration';
import { TokenDetailsStep } from '@/components/register/steps/token-details-step';
import { KycConfigStep, KycCip170Step } from '@/components/register/steps/kyc';
import { SuccessStep } from '@/components/register/steps/success-step';

function RwaTokenSuccessStep(props: StepComponentProps) {
  // The chained kyc-config step does everything (genesis + AddPowerUser +
  // [publishScripts] + registration + [transferLogic cert]) and writes the chain's
  // metadata to its own result.data.
  const chainResult = props.wizardState.stepStates['kyc-config']?.result?.data as {
    globalStatePolicyId?: string;
    programmableTokenPolicyId?: string;
    denylistPolicyId?: string;
    powerUsersPolicyId?: string;
    chainTxHashes?: string[];
    chainTxHashesByName?: Record<string, string | undefined>;
    initialMintQuantity?: string;
  } | undefined;

  // Read the hashes BY NAME. They used to be indexed positionally
  // (chainTxHashes[0|1|2]), which quietly mislabelled every one of them as soon as
  // an optional transaction was inserted before the registration — which is exactly
  // what publishScripts does on the mint path.
  const byName = chainResult?.chainTxHashesByName;

  const enhancedResult = props.wizardState.finalResult || {
    policyId: chainResult?.programmableTokenPolicyId || '',
    txHash: byName?.registration || '',
    substandardId: 'rwa-token',
    assetName: '',
    quantity: chainResult?.initialMintQuantity || '',
    metadata: {
      globalStatePolicyId: chainResult?.globalStatePolicyId,
      denylistPolicyId: chainResult?.denylistPolicyId,
      powerUsersPolicyId: chainResult?.powerUsersPolicyId,
      genesisTxHash: byName?.genesis,
      addPowerUserTxHash: byName?.addPowerUser,
      publishScriptsTxHash: byName?.publishScripts,
      registrationTxHash: byName?.registration,
      registerTransferLogicTxHash: byName?.registerTransferLogic,
    },
  };

  return <SuccessStep {...props} result={enhancedResult} />;
}

const rwaTokenFlow: RegistrationFlow = {
  id: 'rwa-token',
  name: 'RWA Token (German & Swiss profiles)',
  description:
    'Programmable real-world-asset tokens designed as reference profiles supporting the ' +
    'implementation of German (eWpG) and Swiss (OR, ledger-based securities) requirements. ' +
    'Provides KYC-gated transfers, denylisting, global pause, forced transfers and seizures, ' +
    'supply caps, role-based permissions and an irreversible decommission mechanism, plus a ' +
    'metadata schema covering ISIN, terms of issue, issuer details, nominal amount and ' +
    'register/custodian references. Technical functionality only \u2014 it does not imply or ' +
    'ensure legal or regulatory compliance in any jurisdiction.',
  enabled: isFlowEnabled('rwa-token', true),
  steps: [
    {
      id: 'cip170-auth-begin',
      title: 'CIP-170 Auth (Optional)',
      description: 'Publish your credential chain on-chain (only needs to be done once)',
      requiresWalletSign: true,
      component: KycCip170Step as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'token-details',
      title: 'Token Details',
      description: 'Define your token name, supply, and recipient',
      requiresWalletSign: false,
      component: TokenDetailsStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'kyc-config',
      title: 'Configure & Register',
      description: 'Configure the Trusted Entity List, then build + sign + submit the full registration chain (genesis + AddPowerUser + publish reference scripts + registration + transfer-logic cert) in one wallet popup',
      requiresWalletSign: true,
      component: KycConfigStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'success',
      title: 'Complete',
      description: 'Registration complete',
      requiresWalletSign: false,
      component: RwaTokenSuccessStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
  ],
  getInitialData: () => ({}),
  buildRegistrationRequest: (state: WizardState): RwaTokenRegistrationData => {
    const tokenDetails = state.stepStates['token-details']?.data as {
      assetName?: string;
      quantity?: string;
      recipientAddress?: string;
    } | undefined;

    const kycConfig = state.stepStates['kyc-config']?.data as {
      globalStatePolicyId?: string;
      denylistPolicyId?: string;
      powerUsersPolicyId?: string;
      requiresReceiverKyc?: boolean;
    } | undefined;

    return {
      substandardId: 'rwa-token',
      feePayerAddress: '',
      assetName: tokenDetails?.assetName || '',
      quantity: tokenDetails?.quantity || '',
      recipientAddress: tokenDetails?.recipientAddress,
      adminPubKeyHash: '',
      globalStatePolicyId: kycConfig?.globalStatePolicyId || '',
      denylistPolicyId: kycConfig?.denylistPolicyId || '',
      powerUsersPolicyId: kycConfig?.powerUsersPolicyId || '',
      // Defaults to true (the safer regulatory-compliance posture);
      // admins can flip it later via the SetRequiresReceiverKyc spend action.
      requiresReceiverKyc: kycConfig?.requiresReceiverKyc ?? true,
    };
  },
};

registerFlow(rwaTokenFlow);

export { rwaTokenFlow };
