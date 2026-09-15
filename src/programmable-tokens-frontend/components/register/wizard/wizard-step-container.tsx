"use client";

import { useState, useCallback, useMemo } from 'react';
import { useRegistrationWizard } from '@/contexts/registration-wizard-context';
import { useCIP113 } from '@/contexts/cip113-context';
import { useWallet } from '@/hooks/use-wallet';
import { getPaymentKeyHash } from '@/lib/utils/address';
import type { StepResult, StepComponentProps } from '@/types/registration';

export function WizardStepContainer() {
  const {
    state,
    dispatch,
    currentFlow,
    currentStep,
    getStepData,
    canGoBack,
  } = useRegistrationWizard();

  const { registerTokenCallback } = useCIP113();
  const { wallet } = useWallet();
  const [isProcessing, setIsProcessing] = useState(false);

  // Get current step data
  const stepData = useMemo(() => {
    if (!currentStep) return {};
    return getStepData(currentStep.id);
  }, [currentStep, getStepData]);

  // Handle data change
  const handleDataChange = useCallback(
    (data: Record<string, unknown>) => {
      if (!currentStep) return;
      dispatch({
        type: 'UPDATE_STEP_DATA',
        stepId: currentStep.id,
        data,
      });
    },
    [currentStep, dispatch]
  );

  // Handle step completion
  const handleComplete = useCallback(
    async (result: StepResult) => {
      if (!currentStep || !currentFlow) return;

      // Mark step as complete
      dispatch({
        type: 'COMPLETE_STEP',
        stepId: currentStep.id,
        result,
      });

      let registrationRecorded = true;

      // Check if this is the submission step (step right before "success")
      const currentIndex = currentFlow.steps.findIndex((s) => s.id === currentStep.id);
      const isSubmissionStep = currentIndex === currentFlow.steps.length - 2;

      // Fire backend registration callback after submission step
      if (isSubmissionStep && currentFlow.getRegistrationCallbackData) {
        try {
          // Build updated state with this step's result included
          const updatedState = {
            ...state,
            stepStates: {
              ...state.stepStates,
              [currentStep.id]: { ...state.stepStates[currentStep.id], status: 'completed' as const, result },
            },
          };
          const callbackData = currentFlow.getRegistrationCallbackData(updatedState);
          if (callbackData) {
            // ⛔ NO WALLET FALLBACK. This used to fill issuerAdminPkh from
            // `getUsedAddresses()[0]` when the flow left it unset, and a guessed key hash here
            // is not a degraded result — the token's policy id is derived from it, so a wrong
            // guess writes a row describing a DIFFERENT token and every later operation is
            // refused in terms that blame the token. The flow now supplies the pkh the scripts
            // were actually built with; if it ever does not, that is a defect to see, not to
            // paper over.
            if (!callbackData.issuerAdminPkh && callbackData.substandardId === 'freeze-and-seize') {
              throw new Error(
                'The registration flow did not report the admin key hash the scripts were built ' +
                  'with. Refusing to guess it from the wallet: the token policy id is derived ' +
                  'from this value, so a wrong one records a row describing a different token.',
              );
            }
            await registerTokenCallback(callbackData);
            console.log('[Registration] Token registered in backend DB');
          }
        } catch (e) {
          // ⚠ SURFACED, not swallowed. The token is already on chain by this point — this
          // callback is what makes it USABLE from this backend. A console.warn left the
          // operator with a minted token, a missing or wrong row, and a success screen.
          console.error('[Registration] Backend registration callback failed:', e);
          registrationRecorded = false;
          dispatch({
            type: 'SET_STEP_ERROR',
            stepId: currentStep.id,
            error:
              `The token was minted, but recording it in this backend failed: ` +
              `${(e as Error).message} The token exists on chain and is not damaged, but ` +
              `operations on it from this deployment will fail until its record is repaired.`,
          });
        }
      }

      // ⛔ DO NOT advance to the success screen when the record was not written. The token is on
      // chain either way, but "registered" and "usable from this backend" are different claims,
      // and showing success for the first while the second failed is how a broken token reaches
      // an operator looking finished.
      if (registrationRecorded && currentIndex < currentFlow.steps.length - 1) {
        dispatch({ type: 'NEXT_STEP' });
      }
    },
    [currentStep, currentFlow, dispatch, state, registerTokenCallback, wallet]
  );

  // Handle error
  const handleError = useCallback(
    (error: string) => {
      if (!currentStep) return;
      dispatch({
        type: 'SET_STEP_ERROR',
        stepId: currentStep.id,
        error,
      });
    },
    [currentStep, dispatch]
  );

  // Handle back navigation
  const handleBack = useCallback(() => {
    if (canGoBack) {
      dispatch({ type: 'PREV_STEP' });
    }
  }, [canGoBack, dispatch]);

  // Set processing state
  const handleSetProcessing = useCallback((processing: boolean) => {
    setIsProcessing(processing);
  }, []);

  if (!currentStep || !currentFlow) {
    return (
      <div className="text-center py-8 text-dark-400">
        <p>No step to display</p>
      </div>
    );
  }

  // Build props for the step component
  const stepProps: StepComponentProps = {
    stepData,
    onDataChange: handleDataChange,
    onComplete: handleComplete,
    onError: handleError,
    onBack: canGoBack ? handleBack : undefined,
    wizardState: state,
    isProcessing,
    setProcessing: handleSetProcessing,
  };

  // Render the step component
  const StepComponent = currentStep.component;

  return (
    <div className="wizard-step-container">
      <StepComponent {...stepProps} />
    </div>
  );
}
