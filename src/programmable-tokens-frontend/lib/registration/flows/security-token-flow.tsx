/**
 * Security-Token Substandard Flow
 *
 * Adds an optional receiver-KYC toggle, an on-chain denylist, and role-gated
 * "power users" on top of the kyc-extended pattern. The wizard reuses every
 * step component from basic kyc — the registration shape extends the
 * kyc-extended one with the security-token-specific policy ids and the
 * requires_receiver_kyc flag.
 */

import { registerFlow, isFlowEnabled } from '../flow-registry';
import type {
  RegistrationFlow,
  WizardState,
  SecurityTokenRegistrationData,
  StepComponentProps,
} from '@/types/registration';
import { TokenDetailsStep } from '@/components/register/steps/token-details-step';
import { KycConfigStep, KycCip170Step } from '@/components/register/steps/kyc';
import { SuccessStep } from '@/components/register/steps/success-step';

function SecurityTokenSuccessStep(props: StepComponentProps) {
  // The chained kyc-config step does everything (genesis + AddPowerUser +
  // registration) and writes the chain's metadata to its own result.data.
  const chainResult = props.wizardState.stepStates['kyc-config']?.result?.data as {
    globalStatePolicyId?: string;
    programmableTokenPolicyId?: string;
    denylistPolicyId?: string;
    powerUsersPolicyId?: string;
    chainTxHashes?: string[];
  } | undefined;

  const enhancedResult = props.wizardState.finalResult || {
    policyId: chainResult?.programmableTokenPolicyId || '',
    txHash: chainResult?.chainTxHashes?.[2] || '',  // registration tx hash
    substandardId: 'security-token',
    assetName: '',
    quantity: '',
    metadata: {
      globalStatePolicyId: chainResult?.globalStatePolicyId,
      denylistPolicyId: chainResult?.denylistPolicyId,
      powerUsersPolicyId: chainResult?.powerUsersPolicyId,
      genesisTxHash: chainResult?.chainTxHashes?.[0],
      addPowerUserTxHash: chainResult?.chainTxHashes?.[1],
      registrationTxHash: chainResult?.chainTxHashes?.[2],
    },
  };

  return <SuccessStep {...props} result={enhancedResult} />;
}

const securityTokenFlow: RegistrationFlow = {
  id: 'security-token',
  name: 'Security Token (BaFin)',
  description:
    'Regulated-securities substandard with role-gated power users (Admin / Minter / Burner / ' +
    'Pauser / Blacklister / Verifier), an on-chain denylist, and a togglable receiver-KYC ' +
    'requirement. Choose this for tokenised assets that need to meet jurisdictional rules ' +
    '(e.g. BaFin under German eWpG, Swiss CO Art. 973e). Ported from easy1staking-com/fn-bafin-cardano-sc.',
  enabled: isFlowEnabled('security-token', true),
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
      description: 'Configure the Trusted Entity List, then build + sign + submit the full registration chain (genesis + AddPowerUser + registration) in one wallet popup',
      requiresWalletSign: true,
      component: KycConfigStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'success',
      title: 'Complete',
      description: 'Registration complete',
      requiresWalletSign: false,
      component: SecurityTokenSuccessStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
  ],
  getInitialData: () => ({}),
  buildRegistrationRequest: (state: WizardState): SecurityTokenRegistrationData => {
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
      substandardId: 'security-token',
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

registerFlow(securityTokenFlow);

export { securityTokenFlow };
