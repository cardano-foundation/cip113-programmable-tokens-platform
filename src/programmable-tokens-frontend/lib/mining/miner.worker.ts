/**
 * The mining worker.
 *
 * ⛔ THIS MUST NOT RUN ON THE MAIN THREAD. The search is a tight synchronous loop doing tens of
 * thousands of hashes a second; on the main thread the tab stops painting and stops responding to
 * clicks — including the cancel button that exists to stop it. A user looking at a frozen page
 * reloads it, and a reload during mining loses nothing but their time, which is the thing mining
 * was spending on their behalf.
 *
 * Cancellation is a flag checked on a progress tick rather than a message handled mid-loop: a
 * worker processes messages between turns of the event loop, and this loop does not yield.
 */
import { mineLowTxHash, calibrate, type LovelaceSlot } from './mine';

export type MinerRequest =
  | { kind: 'calibrate'; body: Uint8Array; milliseconds?: number }
  | {
      kind: 'mine';
      body: Uint8Array;
      gains: LovelaceSlot;
      loses: LovelaceSlot;
      targetNibbles: number;
      maxAttempts: number;
    }
  | { kind: 'cancel' };

export type MinerResponse =
  | { kind: 'calibrated'; hashesPerSecond: number }
  | { kind: 'progress'; attempts: number }
  | {
      kind: 'done';
      found: boolean;
      cancelled: boolean;
      attempts: number;
      nonce: number;
      txHash: string;
      body: Uint8Array;
    }
  | { kind: 'failed'; message: string };

let cancelRequested = false;

self.onmessage = (event: MessageEvent<MinerRequest>) => {
  const request = event.data;

  if (request.kind === 'cancel') {
    cancelRequested = true;
    return;
  }

  try {
    if (request.kind === 'calibrate') {
      const hashesPerSecond = calibrate(request.body, request.milliseconds);
      post({ kind: 'calibrated', hashesPerSecond });
      return;
    }

    cancelRequested = false;
    const result = mineLowTxHash({
      body: request.body,
      gains: request.gains,
      loses: request.loses,
      targetNibbles: request.targetNibbles,
      maxAttempts: request.maxAttempts,
      onProgress: (attempts) => post({ kind: 'progress', attempts }),
      shouldCancel: () => cancelRequested,
    });

    post({
      kind: 'done',
      found: result.found,
      cancelled: result.cancelled,
      attempts: result.attempts,
      nonce: result.nonce,
      txHash: result.txHash,
      body: result.body,
    });
  } catch (e) {
    // Reported, never swallowed. A miner that dies quietly leaves a spinner turning forever.
    post({ kind: 'failed', message: (e as Error).message });
  }
};

function post(response: MinerResponse): void {
  (self as unknown as { postMessage: (m: MinerResponse) => void }).postMessage(response);
}
