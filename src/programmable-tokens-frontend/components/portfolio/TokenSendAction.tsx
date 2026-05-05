"use client";

import { Send } from "lucide-react";

interface Props {
  policyId: string;
  tokenName: string;
  walletAddress: string | null;
  onSend: () => void;
}

/**
 * Per-row Send button with no kyc-extended gating.
 *
 * The Aiken validator filters sender pkhs out of receiver_witnesses, so a holder
 * is always allowed to initiate a send — they authenticate via Attestation
 * (cookie or KERI). Membership is required only for the recipient. The transfer
 * modal handles the recipient probe and gates submission accordingly.
 *
 * (Kept as a wrapper to leave room for future per-token gating like
 * "transfers paused" or balance-based affordances.)
 */
export function TokenSendAction({ policyId: _policyId, tokenName, walletAddress: _walletAddress, onSend }: Props) {
  return <SendButton onClick={onSend} title={`Transfer ${tokenName}`} />;
}

function SendButton({ onClick, title }: { onClick: () => void; title: string }) {
  return (
    <button
      onClick={onClick}
      className="p-1.5 hover:bg-dark-700 rounded transition-colors"
      title={title}
    >
      <Send className="h-4 w-4 text-primary-400 hover:text-primary-300" />
    </button>
  );
}
