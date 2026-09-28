'use client';

/**
 * Driving the miner from React: calibrate on this machine, then search, with a way out.
 *
 * Every estimate this exposes comes from a rate MEASURED here, never from a constant. The
 * reference figure is ~28,000 h/s over a 2 KB body on a developer workstation, and a phone is a
 * different instrument entirely — showing someone a wall-clock estimate is only honest if the
 * number came from the machine that will do the work.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { MinerRequest, MinerResponse } from './miner.worker';
import type { LovelaceSlot } from './mine';
import { DEFAULT_TARGET_NIBBLES, expectedSeconds, humaniseSeconds } from './target';

export interface MiningState {
  phase: 'idle' | 'calibrating' | 'mining' | 'done' | 'failed';
  /** Measured on this machine, over a body of the size actually being mined. */
  hashesPerSecond: number | null;
  attempts: number;
  txHash: string | null;
  minedBody: Uint8Array | null;
  /** Lovelace moved from change into the self-output. */
  nonce: number;
  error: string | null;
  cancelled: boolean;
}

const IDLE: MiningState = {
  phase: 'idle', hashesPerSecond: null, attempts: 0,
  txHash: null, minedBody: null, nonce: 0, error: null, cancelled: false,
};

export function useMiner() {
  const workerRef = useRef<Worker | null>(null);
  const [state, setState] = useState<MiningState>(IDLE);

  const ensureWorker = useCallback((): Worker => {
    if (!workerRef.current) {
      workerRef.current = new Worker(new URL('./miner.worker.ts', import.meta.url));
      workerRef.current.onmessage = (event: MessageEvent<MinerResponse>) => {
        const message = event.data;
        switch (message.kind) {
          case 'calibrated':
            setState((s) => ({ ...s, hashesPerSecond: message.hashesPerSecond, phase: 'idle' }));
            break;
          case 'progress':
            setState((s) => ({ ...s, attempts: message.attempts }));
            break;
          case 'done':
            setState((s) => ({
              ...s,
              phase: 'done',
              attempts: message.attempts,
              txHash: message.found ? message.txHash : null,
              minedBody: message.found ? message.body : null,
              nonce: message.nonce,
              cancelled: message.cancelled,
            }));
            break;
          case 'failed':
            setState((s) => ({ ...s, phase: 'failed', error: message.message }));
            break;
        }
      };
    }
    return workerRef.current;
  }, []);

  // A worker outlives the component unless it is told not to. Mining is expensive and a leaked
  // one keeps a core busy after the page has moved on.
  useEffect(() => () => { workerRef.current?.terminate(); workerRef.current = null; }, []);

  const send = useCallback((request: MinerRequest) => { ensureWorker().postMessage(request); }, [ensureWorker]);

  const calibrate = useCallback((body: Uint8Array, milliseconds = 300) => {
    setState((s) => ({ ...s, phase: 'calibrating', error: null }));
    send({ kind: 'calibrate', body, milliseconds });
  }, [send]);

  const mine = useCallback((params: {
    body: Uint8Array;
    gains: LovelaceSlot;
    loses: LovelaceSlot;
    targetNibbles?: number;
    maxAttempts?: number;
  }) => {
    const targetNibbles = params.targetNibbles ?? DEFAULT_TARGET_NIBBLES;
    setState({ ...IDLE, phase: 'mining', hashesPerSecond: state.hashesPerSecond });
    send({
      kind: 'mine',
      body: params.body,
      gains: params.gains,
      loses: params.loses,
      targetNibbles,
      // Ten times the expectation, so an unlucky search still finishes rather than looking hung.
      // The expectation is 16^n; exceeding ten times it has probability about 4.5e-5.
      maxAttempts: params.maxAttempts ?? Math.pow(16, targetNibbles) * 10,
    });
  }, [send, state.hashesPerSecond]);

  const cancel = useCallback(() => { send({ kind: 'cancel' }); }, [send]);

  const reset = useCallback(() => setState((s) => ({ ...IDLE, hashesPerSecond: s.hashesPerSecond })), []);

  /** Wall-clock for a target, from the measured rate. Null until calibration has run. */
  const estimate = useCallback((targetNibbles: number): string | null => {
    if (!state.hashesPerSecond) return null;
    return humaniseSeconds(expectedSeconds(targetNibbles, state.hashesPerSecond));
  }, [state.hashesPerSecond]);

  return { state, calibrate, mine, cancel, reset, estimate };
}
