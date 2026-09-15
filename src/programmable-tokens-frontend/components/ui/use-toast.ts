"use client";

import { useState, useCallback, useEffect } from "react";

export type ToastVariant = "default" | "success" | "error" | "warning" | "info";

export interface Toast {
  id: string;
  title?: string;
  description?: string;
  variant?: ToastVariant;
  duration?: number;
}

interface ToastState {
  toasts: Toast[];
}

let toastCounter = 0;
const listeners = new Set<(state: ToastState) => void>();
let memoryState: ToastState = { toasts: [] };

/**
 * How long a toast stays, by kind.
 *
 * ⛔ AN ERROR DOES NOT AUTO-DISMISS. Reported first-hand: "the thing disappears too quickly
 * lol" — an error naming a policy id and a derived hash was gone before it could be read, let
 * alone copied. These messages are the whole diagnostic surface for a failed on-chain
 * operation, and five seconds is not enough to read 56 hex characters, never mind act on them.
 *
 * Success and info still clear themselves: they say something happened, and the thing that
 * happened is visible elsewhere. An error is the only record the operator gets.
 */
function defaultDurationFor(variant: ToastVariant | undefined): number {
  return variant === "error" || variant === "warning" ? Infinity : 5000;
}

function dispatch(action: { type: string; toast?: Toast; toastId?: string }) {
  if (action.type === "ADD_TOAST") {
    const id = (++toastCounter).toString();
    const toast = {
      ...action.toast!,
      id,
      duration: action.toast!.duration ?? defaultDurationFor(action.toast!.variant),
    };

    memoryState = { toasts: [...memoryState.toasts, toast] };

    if (toast.duration !== Infinity) {
      setTimeout(() => {
        dispatch({ type: "DISMISS_TOAST", toastId: id });
      }, toast.duration);
    }
  } else if (action.type === "DISMISS_TOAST") {
    memoryState = { toasts: memoryState.toasts.filter((t) => t.id !== action.toastId) };
  } else if (action.type === "REMOVE_TOAST") {
    memoryState = { toasts: memoryState.toasts.filter((t) => t.id !== action.toastId) };
  }

  listeners.forEach((listener) => listener(memoryState));
}

export function useToast() {
  const [state, setState] = useState<ToastState>(memoryState);

  useEffect(() => {
    listeners.add(setState);
    return () => {
      listeners.delete(setState);
    };
  }, []);

  const toast = useCallback(
    (props: Omit<Toast, "id">) => {
      dispatch({ type: "ADD_TOAST", toast: props as Toast });
    },
    []
  );

  const dismiss = useCallback((toastId: string) => {
    dispatch({ type: "DISMISS_TOAST", toastId });
  }, []);

  return {
    toasts: state.toasts,
    toast,
    dismiss,
  };
}
