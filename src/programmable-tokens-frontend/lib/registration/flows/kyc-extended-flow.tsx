/**
 * KYC-Extended Substandard Flow
 *
 * Like the basic KYC flow, but receivers must also be in an on-chain allowlist.
 * Whenever a user finishes KYC for this token, the backend automatically inserts
 * their wallet into a Merkle Patricia Forestry tree whose root hash is published
 * on-chain on a regular cycle. Transfers are rejected if the recipient hasn't
 * gone through KYC yet. Choose this when you need both sides verified — cost:
 * receivers must onboard before they can receive.
 *
 * The wizard reuses every step component from the basic kyc flow (token-details,
 * kyc-config, kyc-cip170, kyc-build-sign, success). Only the substandardId,
 * name, description, and the buildRegistrationRequest discriminator differ.
 */

import { registerFlow, isFlowEnabled } from '../flow-registry';
import type {
  RegistrationFlow,
  WizardState,
  KycExtendedRegistrationData,
  StepComponentProps,
} from '@/types/registration';
import { TokenDetailsStep } from '@/components/register/steps/token-details-step';
import { KycConfigStep, KycCip170Step, KycBuildSignSubmitStep } from '@/components/register/steps/kyc';
import { SuccessStep } from '@/components/register/steps/success-step';

function KycExtendedSuccessStep(props: StepComponentProps) {
  const buildResult = props.wizardState.stepStates['kyc-build-sign']?.result?.data as {
    tokenPolicyId?: string;
    regTxHash?: string;
    globalStatePolicyId?: string;
  } | undefined;

  const enhancedResult = props.wizardState.finalResult || {
    policyId: buildResult?.tokenPolicyId || '',
    txHash: buildResult?.regTxHash || '',
    substandardId: 'kyc-extended',
    assetName: '',
    quantity: '',
    metadata: {
      globalStatePolicyId: buildResult?.globalStatePolicyId,
    },
  };

  return <SuccessStep {...props} result={enhancedResult} />;
}

const kycExtendedFlow: RegistrationFlow = {
  id: 'kyc-extended',
  name: 'KYC Token (Extended)',
  description:
    'Like the basic KYC token, but receivers must also be in an on-chain allowlist. ' +
    'Whenever a user finishes KYC for this token, their wallet is added to a Merkle ' +
    'Patricia Forestry tree maintained by the backend. The tree\'s root hash is ' +
    'published on-chain on a regular cycle. Transfers are rejected if the recipient ' +
    'hasn\'t gone through KYC yet. Choose this when you need both sides verified — ' +
    'cost: receivers must onboard before they can receive.',
  enabled: isFlowEnabled('kyc-extended', true),
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
      title: 'KYC Configuration',
      description: 'Configure the Trusted Entity List for KYC verification',
      requiresWalletSign: false,
      component: KycConfigStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'kyc-build-sign',
      title: 'Build & Sign',
      description: 'Build, sign, and submit the registration transaction',
      requiresWalletSign: true,
      component: KycBuildSignSubmitStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'success',
      title: 'Complete',
      description: 'Registration complete',
      requiresWalletSign: false,
      component: KycExtendedSuccessStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
  ],
  getInitialData: () => ({}),
  buildRegistrationRequest: (state: WizardState): KycExtendedRegistrationData => {
    const tokenDetails = state.stepStates['token-details']?.data as {
      assetName?: string;
      quantity?: string;
      recipientAddress?: string;
    } | undefined;

    const kycConfig = state.stepStates['kyc-config']?.data as {
      globalStatePolicyId?: string;
    } | undefined;

    return {
      substandardId: 'kyc-extended',
      feePayerAddress: '',
      assetName: tokenDetails?.assetName || '',
      quantity: tokenDetails?.quantity || '',
      recipientAddress: tokenDetails?.recipientAddress,
      adminPubKeyHash: '',
      globalStatePolicyId: kycConfig?.globalStatePolicyId || '',
    };
  },
};

registerFlow(kycExtendedFlow);

export { kycExtendedFlow };
