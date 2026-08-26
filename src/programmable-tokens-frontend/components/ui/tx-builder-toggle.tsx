"use client";

import { cn } from "@/lib/utils/cn";

export type TransactionBuilder = "sdk" | "backend";

interface TxBuilderToggleProps {
  value: TransactionBuilder;
  onChange: (builder: TransactionBuilder) => void;
  sdkAvailable: boolean;
  /** Shown on hover when the SDK option is disabled. */
  sdkUnavailableReason?: string;
  className?: string;
}

const DEFAULT_UNAVAILABLE_REASON =
  "The CIP-113 TypeScript SDK has not been upgraded for the current core contracts. "
  + "Transactions are built by the Java backend.";

/**
 * Choose which side builds the transaction.
 *
 * <p>When the SDK is unavailable this used to render nothing, which left the UI silently
 * different with no way to tell whether the option had been removed, had failed, or had
 * never existed. It now renders the choice with the SDK side DISABLED and the reason on
 * hover: the capability is still visible, and its absence is explained where someone would
 * look for it.
 */
export function TxBuilderToggle({
  value,
  onChange,
  sdkAvailable,
  sdkUnavailableReason,
  className,
}: TxBuilderToggleProps) {
  const reason = sdkUnavailableReason ?? DEFAULT_UNAVAILABLE_REASON;

  return (
    <div className={cn("flex items-center justify-between px-3 py-2 bg-dark-900 rounded-lg", className)}>
      <span className="text-xs text-dark-400">Tx Builder</span>
      <div className="flex items-center gap-2">
        {!sdkAvailable && (
          // `title` gives the hover box without pulling in a tooltip dependency, and stays
          // reachable by keyboard focus and by screen readers.
          <span
            className="text-[10px] uppercase tracking-wide text-amber-400/80 cursor-help"
            title={reason}
            tabIndex={0}
            aria-label={reason}
          >
            SDK unavailable
          </span>
        )}
        <div className="flex gap-1 bg-dark-800 rounded-md p-0.5">
          <button
            type="button"
            onClick={() => sdkAvailable && onChange("sdk")}
            disabled={!sdkAvailable}
            title={sdkAvailable ? undefined : reason}
            aria-disabled={!sdkAvailable}
            className={cn(
              "px-3 py-1 text-xs rounded transition-colors",
              !sdkAvailable
                ? "text-dark-600 cursor-not-allowed line-through"
                : value === "sdk"
                  ? "bg-primary-500 text-white"
                  : "text-dark-400 hover:text-white"
            )}
          >
            SDK
          </button>
          <button
            type="button"
            onClick={() => onChange("backend")}
            className={cn(
              "px-3 py-1 text-xs rounded transition-colors",
              value === "backend"
                ? "bg-primary-500 text-white"
                : "text-dark-400 hover:text-white"
            )}
          >
            Backend
          </button>
        </div>
      </div>
    </div>
  );
}
