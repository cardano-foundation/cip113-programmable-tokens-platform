/**
 * KYC Substandard Flow
 * Token registration with KYC attestation requirements for transfers.
 * Requires a Global State to be created externally.
 */

import { registerFlow, isFlowEnabled } from '../flow-registry';
import type {
  RegistrationFlow,
  WizardState,
  KycRegistrationData,
  StepComponentProps,
} from '@/types/registration';
import { TokenDetailsStep } from '@/components/register/steps/token-details-step';
import { KycConfigStep, KycCip170Step, KycBuildSignSubmitStep } from '@/components/register/steps/kyc';
import { SuccessStep } from '@/components/register/steps/success-step';

function KycSuccessStep(props: StepComponentProps) {
  const buildResult = props.wizardState.stepStates['kyc-build-sign']?.result?.data as {
    tokenPolicyId?: string;
    regTxHash?: string;
    globalStatePolicyId?: string;
  } | undefined;

  const enhancedResult = props.wizardState.finalResult || {
    policyId: buildResult?.tokenPolicyId || '',
    txHash: buildResult?.regTxHash || '',
    substandardId: 'kyc',
    assetName: '',
    quantity: '',
    metadata: {
      globalStatePolicyId: buildResult?.globalStatePolicyId,
    },
  };

  return <SuccessStep {...props} result={enhancedResult} />;
}

const kycFlow: RegistrationFlow = {
  id: 'kyc',
  name: 'KYC Token',
  description: 'Programmable token requiring KYC attestation for transfers. Uses a Global State for verifying transfer authorization.',
  enabled: isFlowEnabled('kyc', true),
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
      component: KycSuccessStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
  ],
  getInitialData: () => ({}),
  buildRegistrationRequest: (state: WizardState): KycRegistrationData => {
    const tokenDetails = state.stepStates['token-details']?.data as {
      assetName?: string;
      quantity?: string;
      recipientAddress?: string;
    } | undefined;

    const kycConfig = state.stepStates['kyc-config']?.data as {
      globalStatePolicyId?: string;
    } | undefined;

    return {
      substandardId: 'kyc',
      feePayerAddress: '',
      assetName: tokenDetails?.assetName || '',
      quantity: tokenDetails?.quantity || '',
      recipientAddress: tokenDetails?.recipientAddress,
      adminPubKeyHash: '',
      globalStatePolicyId: kycConfig?.globalStatePolicyId || '',
    };
  },
};

registerFlow(kycFlow);

export { kycFlow };
