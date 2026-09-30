/**
 * What a ceremony needs to survive a lost tab, and deliberately nothing more.
 *
 * ## Why the INPUTS and not the transactions
 *
 * A bootstrap plan is a pure function of a handful of values — three seed outrefs, the always_fail
 * nonce, the inline-datum bound, the unfracking choice, the member list and the threshold — plus the
 * pinned blueprint. Re-entering those re-derives byte-identical scripts, addresses and hashes. So
 * persisting the built transactions would be storing a cache of something reproducible, and worse:
 * a stored transaction can go stale against the chain while still looking usable, which is the whole
 * family of bug this ceremony has already been bitten by four times.
 *
 * The transaction HASHES are different and are stored. They are results, not plans — nothing can
 * re-derive them, the bootstrap record needs the genesis hash, and they are public.
 *
 * ## ⛔ WHAT THIS CANNOT RECOVER
 *
 * Collected witnesses are NOT stored. A witness commits to one transaction body, and phase two's
 * genesis is rebuilt on resume against a fresh UTxO set — so every stored witness would verify
 * against nothing and the panel would say "does not verify" for every participant, which reads as
 * their fault. Signatures are re-collected on resume; the copy has to say so.
 *
 * ## Why localStorage, and what that means
 *
 * A ceremony runs in one operator's browser and must survive a reload, not a machine change.
 * Nothing here is a credential: the nonce is a parameter whose only product is `always_fail`, a
 * script that can never succeed, and the rest is already on chain or about to be. It is still
 * scoped per network, because restoring a preview ceremony into a preprod build would derive a
 * plan for the wrong chain and only fail at submission.
 */

import { BOOTSTRAP_STEPS } from "@easy1staking/cip113-sdk-ts";
import type { CardanoNetwork } from "../utils/network";

/** Bump when the shape changes. A stored ceremony from an older shape is DISCARDED, not migrated. */
export const CEREMONY_STORAGE_VERSION = 1;

const key = (network: CardanoNetwork) => `cip113.ceremony.${network}`;

export interface StoredOutref {
  txHash: string;
  outputIndex: string;
}

/** Exactly the values `deriveCoreDeployment` and `resolveMultisig` consume. Nothing else. */
export interface CeremonyInputs {
  paramsSeed: StoredOutref;
  issuanceSeed: StoredOutref;
  multisigSeed: StoredOutref;
  nonce: string;
  maxInlineDatumBytes: string;
  unfrackingEnabled: boolean;
  membersText: string;
  threshold: string;
}

export interface StoredStep {
  /** A `BootstrapStepId` — driven off the SDK's own list, never a string we invented. */
  step: string;
  txHash: string;
}

export interface StoredCeremony {
  version: number;
  network: CardanoNetwork;
  savedAt: number;
  /** The account the plan was built for, so a resume can say whether the wallet has moved. */
  changeAddress: string | null;
  inputs: CeremonyInputs;
  submitted: readonly StoredStep[];
}

function isOutref(v: unknown): v is StoredOutref {
  const o = v as StoredOutref | undefined;
  return !!o && typeof o.txHash === "string" && typeof o.outputIndex === "string";
}

/**
 * Read a stored ceremony back, or null.
 *
 * ⛔ VALIDATES RATHER THAN TRUSTS. localStorage is a string a user, an extension or a previous
 * version of this code can have written, and a half-shaped object here would surface as a
 * derivation error five steps later with nothing pointing at the cause. Anything unrecognised is
 * discarded and reported as absent, which is the state the operator can act on.
 */
export function loadCeremony(
  network: CardanoNetwork,
  storage: Pick<Storage, "getItem" | "setItem" | "removeItem"> | null = safeStorage(),
): StoredCeremony | null {
  if (!storage) return null;
  let raw: string | null;
  try {
    raw = storage.getItem(key(network));
  } catch {
    return null;
  }
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  const c = parsed as StoredCeremony | undefined;
  if (!c || c.version !== CEREMONY_STORAGE_VERSION) return null;
  // ⚑ A ceremony saved on ANOTHER network must not load here: the same inputs derive a plan for
  // whichever chain the build targets, and the mismatch would only appear at submission.
  if (c.network !== network) return null;
  const i = c.inputs;
  if (!i || !isOutref(i.paramsSeed) || !isOutref(i.issuanceSeed) || !isOutref(i.multisigSeed)) {
    return null;
  }
  if (typeof i.nonce !== "string" || typeof i.membersText !== "string") return null;
  if (!Array.isArray(c.submitted)) return null;
  const submitted = c.submitted.filter(
    (s): s is StoredStep =>
      !!s &&
      typeof s.txHash === "string" &&
      typeof s.step === "string" &&
      (BOOTSTRAP_STEPS as readonly string[]).includes(s.step),
  );
  return { ...c, submitted };
}

export function saveCeremony(
  ceremony: Omit<StoredCeremony, "version" | "savedAt">,
  storage: Pick<Storage, "getItem" | "setItem" | "removeItem"> | null = safeStorage(),
): void {
  if (!storage) return;
  const payload: StoredCeremony = {
    ...ceremony,
    version: CEREMONY_STORAGE_VERSION,
    savedAt: Date.now(),
  };
  try {
    storage.setItem(key(ceremony.network), JSON.stringify(payload));
  } catch {
    /* a full or blocked store must never break a ceremony that is otherwise fine */
  }
}

export function clearCeremony(
  network: CardanoNetwork,
  storage: Pick<Storage, "getItem" | "setItem" | "removeItem"> | null = safeStorage(),
): void {
  if (!storage) return;
  try {
    storage.removeItem(key(network));
  } catch {
    /* nothing to do about it, and nothing depends on it */
  }
}

/** Which of the four live steps a stored ceremony already has on chain. */
export function submittedSteps(c: StoredCeremony | null): Set<string> {
  return new Set((c?.submitted ?? []).map((s) => s.step));
}

/** Private-window and blocked-storage safe: every accessor here can throw, not just return null. */
function safeStorage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}
