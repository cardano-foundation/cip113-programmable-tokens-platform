/**
 * Freeze-and-Seize Module Flow
 * Token registration with compliance features (freeze addresses, seize tokens)
 *
 * Uses combined build-sign-submit step: builds init + registration txs together,
 * signs via CIP-103 signTxs (single wallet popup), submits sequentially.
 */

import { registerFlow, isFlowEnabled } from '../flow-registry';
import type {
  RegistrationFlow,
  WizardState,
  FreezeAndSeizeRegistrationData,
  StepComponentProps,
  TokenRegistrationCallbackData,
} from '@/types/registration';
import { stringToHex } from '@/lib/api';
import { TokenDetailsStep } from '@/components/register/steps/token-details-step';
import { CombinedBuildSignSubmitStep } from '@/components/register/steps/freeze-and-seize';
import { SuccessStep } from '@/components/register/steps/success-step';

// Custom success step that reads from the combined step's result
function FreezeSeizeSuccessStep(props: StepComponentProps) {
  const combinedResult = props.wizardState.stepStates['combined-build-sign']?.result?.data as {
    blacklistNodePolicyId?: string;
    initTxHash?: string;
    tokenPolicyId?: string;
    regTxHash?: string;
  } | undefined;

  // Build enhanced result with blacklist info
  const enhancedResult = props.wizardState.finalResult || {
    policyId: combinedResult?.tokenPolicyId || '',
    txHash: combinedResult?.regTxHash || '',
    moduleId: 'freeze-and-seize',
    assetName: '',
    quantity: '',
    metadata: {
      blacklistNodePolicyId: combinedResult?.blacklistNodePolicyId,
      blacklistInitTxHash: combinedResult?.initTxHash,
    },
  };

  return <SuccessStep {...props} result={enhancedResult} />;
}

const freezeAndSeizeFlow: RegistrationFlow = {
  id: 'freeze-and-seize',
  name: 'Freeze & Seize Token',
  description: 'Programmable token with compliance features: freeze addresses and seize tokens from frozen accounts.',
  enabled: isFlowEnabled('freeze-and-seize', true), // Default: enabled
  steps: [
    {
      id: 'token-details',
      title: 'Token Details',
      description: 'Define your token name, supply, and recipient',
      requiresWalletSign: false,
      component: TokenDetailsStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'combined-build-sign',
      title: 'Build & Sign',
      description: 'Build, sign, and submit both transactions',
      requiresWalletSign: true,
      component: CombinedBuildSignSubmitStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
    {
      id: 'success',
      title: 'Complete',
      description: 'Registration complete',
      requiresWalletSign: false,
      component: FreezeSeizeSuccessStep as React.ComponentType<StepComponentProps<unknown, unknown>>,
    },
  ],
  getInitialData: () => ({}),
  getRegistrationCallbackData: (state: WizardState): TokenRegistrationCallbackData | null => {
    const tokenDetails = state.stepStates['token-details']?.data as {
      assetName?: string;
      cip68Metadata?: { enabled?: boolean };
    } | undefined;
    const combinedResult = state.stepStates['combined-build-sign']?.result?.data as {
      tokenPolicyId?: string;
      blacklistNodePolicyId?: string;
      adminPkh?: string;
      blacklistInitTxInput?: { txHash: string; outputIndex: number };
      userAssetNameHex?: string;
    } | undefined;
    if (!combinedResult?.tokenPolicyId) return null;

    // ⛔ NO SILENT FALLBACK TO THE UNLABELLED NAME FOR A CIP-68 TOKEN, for the same reason the
    // adminPkh guard below exists: issuer_admin is parameterised by (adminPkh, assetName) and the
    // policy id is the hash of the issuance_mint built on that, so a wrong asset name here records
    // a row describing a DIFFERENT token.
    //
    // ⚠ MEASURED 2026-10-01 on preprod, via the SDK path. The submit step's onComplete omitted
    // `userAssetNameHex`, this expression fell back to the raw name, and the backend refused every
    // later lookup with "the stored row belongs to a different token … derive policy 602030fa…"
    // against a real policy of b6e7a4ad…. The token and the chain were both fine.
    //
    // Falling back is still right when CIP-68 is OFF: there is no label, so the raw hex IS the
    // minted name. It is only a lie when a label was applied.
    const cip68Enabled = !!tokenDetails?.cip68Metadata?.enabled;
    if (cip68Enabled && !combinedResult.userAssetNameHex) {
      throw new Error(
        'The registration flow did not report the LABELLED asset name this CIP-68 token was ' +
          'minted with. Refusing to fall back to the unlabelled name: the token policy id is ' +
          'derived from (adminPkh, assetName), so the unlabelled form records a row describing a ' +
          'different token and every later operation is refused in terms that blame the token.',
      );
    }

    return {
      policyId: combinedResult.tokenPolicyId,
      moduleId: 'freeze-and-seize',
      // The full asset name hex AS MINTED — labelled when CIP-68 applied a CIP-67 label.
      assetName: combinedResult.userAssetNameHex || stringToHex(tokenDetails?.assetName || ''),
      blacklistNodePolicyId: combinedResult.blacklistNodePolicyId,
      // ⛔ THE PKH THE SCRIPTS WERE ACTUALLY BUILT WITH, not one re-derived from the wallet.
      //
      // This field was left for WizardStepContainer to fill from `getUsedAddresses()[0]`, and
      // the token's own policy id is DERIVED from it — issuer_admin is parameterised by
      // (adminPkh, assetName) and the policy id is the hash of the issuance_mint built on that.
      // Registration used `paymentCredentialHash(adminAddress)`, the admin address chosen in the
      // form. On any multi-address wallet the first used address is a different key, so the
      // stored row derived a DIFFERENT policy id and every later operation on the token was
      // refused with "Token policy <real> does not match this FES instance <derived>" — which
      // names the token, though the token was never the problem.
      //
      // The correct value was in this same object the whole time; it was just being written to
      // `blacklistAdminPkh` only. Both are the one `adminPkh` the build returned.
      issuerAdminPkh: combinedResult.adminPkh,
      blacklistAdminPkh: combinedResult.adminPkh,
      blacklistInitTxHash: combinedResult.blacklistInitTxInput?.txHash,
      blacklistInitOutputIndex: combinedResult.blacklistInitTxInput?.outputIndex,
      // What the init actually registered. The backend cross-checks this at registration time,
      // and a row that does not carry it disables that check — so state it explicitly rather
      // than letting it default to "unknown".
      cip68Enabled,
    };
  },
  buildRegistrationRequest: (state: WizardState): FreezeAndSeizeRegistrationData => {
    const tokenDetails = state.stepStates['token-details']?.data as {
      assetName?: string;
      quantity?: string;
      recipientAddress?: string;
    } | undefined;

    const combinedResult = state.stepStates['combined-build-sign']?.result?.data as {
      blacklistNodePolicyId?: string;
    } | undefined;

    return {
      moduleId: 'freeze-and-seize',
      feePayerAddress: '', // Will be filled by the step
      assetName: tokenDetails?.assetName || '',
      quantity: tokenDetails?.quantity || '',
      recipientAddress: tokenDetails?.recipientAddress,
      adminPubKeyHash: '', // Will be derived from feePayerAddress by backend
      blacklistNodePolicyId: combinedResult?.blacklistNodePolicyId || '',
    };
  },
};

// Register the flow
registerFlow(freezeAndSeizeFlow);

export { freezeAndSeizeFlow };
