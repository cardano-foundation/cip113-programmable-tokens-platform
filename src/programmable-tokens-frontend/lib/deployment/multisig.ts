/**
 * Upgrade-multisig membership: what a deployer types, turned into what the chain stores.
 *
 * Members are given as payment key hashes or as bech32 addresses. An address is reduced to its
 * PAYMENT credential — a stake part is irrelevant to signing and silently accepting one would
 * store a credential that can never sign.
 *
 * The result is an `at-least` tree of `signature` nodes: M-of-N. The SDK's
 * `multisigScriptDatum` enforces upstream's `well_formed` (28-byte hashes, no duplicates,
 * 1 <= required <= N, at most MULTISIG_MAX_SIZE nodes); this validates first so a deployer sees
 * which entry is wrong rather than a single rejection from the encoder.
 */
import { MULTISIG_MAX_SIZE, multisigScriptDatum } from "@easy1staking/cip113-sdk-ts";
import type { MultisigScriptTree } from "@easy1staking/cip113-sdk-ts";
import { Address as EvoAddress, Bytes } from "@evolution-sdk/evolution";

const PKH_HEX = /^[0-9a-fA-F]{56}$/;

export interface MemberInput {
  /** As typed: a 56-hex payment key hash, or a bech32 address. */
  raw: string;
}

export interface ResolvedMember {
  raw: string;
  keyHash: string;
  /** How it was read, so the UI can show what it did with an address. */
  source: "key-hash" | "address";
}

export class MultisigInputError extends Error {}

/** One entry -> a payment key hash, or a reason it cannot be one. */
export function resolveMember(raw: string): ResolvedMember {
  const value = raw.trim();
  if (value.length === 0) {
    throw new MultisigInputError("empty entry");
  }
  if (PKH_HEX.test(value)) {
    return { raw: value, keyHash: value.toLowerCase(), source: "key-hash" };
  }

  let details: unknown;
  try {
    details = EvoAddress.fromBech32(value);
  } catch {
    throw new MultisigInputError(
      `"${value}" is neither a 56-character payment key hash nor a bech32 address`,
    );
  }

  /**
   * ⛔ `_tag` AND `Bytes.toHex`, not `type` and not `.toLowerCase()`.
   *
   * Evolution returns `paymentCredential` as a tagged class — `{ _tag: "KeyHash" | "ScriptHash",
   * hash: Uint8Array }`. Both mistakes were live and only one of them was loud:
   *
   *  - `hash.toLowerCase()` is a TypeError on a Uint8Array. Reported from a real deployment
   *    attempt as "entry 1: payment.hash.toLowerCase is not a function", which reads as a
   *    problem with the operator's input and is not.
   *  - reading `payment.type` returns `undefined` for EVERY address, so the script-credential
   *    guard below was dead code. A script address would have passed the check that exists to
   *    reject it — and a script cannot sign, so it would have become a member of an authority
   *    it can never satisfy.
   */
  const payment = (details as { paymentCredential?: { hash?: Uint8Array; _tag?: string } })
    ?.paymentCredential;
  if (!payment?.hash) {
    throw new MultisigInputError(`"${value}" has no payment credential`);
  }
  if (payment._tag !== "KeyHash") {
    throw new MultisigInputError(
      `"${value}" has a ${payment._tag === "ScriptHash" ? "SCRIPT" : `"${payment._tag}"`} ` +
        "payment credential. A multisig member must be a key that can sign; a script cannot.",
    );
  }
  return { raw: value, keyHash: Bytes.toHex(payment.hash).toLowerCase(), source: "address" };
}

export interface ResolvedMultisig {
  members: ResolvedMember[];
  required: number;
  tree: MultisigScriptTree;
  /** The datum the upgrade-multisig UTxO carries. */
  datum: ReturnType<typeof multisigScriptDatum>;
}

export function resolveMultisig(entries: readonly string[], required: number): ResolvedMultisig {
  const members: ResolvedMember[] = [];
  const problems: string[] = [];

  entries.forEach((raw, i) => {
    try {
      members.push(resolveMember(raw));
    } catch (e) {
      problems.push(`entry ${i + 1}: ${(e as Error).message}`);
    }
  });
  if (problems.length > 0) {
    throw new MultisigInputError(problems.join("; "));
  }
  if (members.length === 0) {
    throw new MultisigInputError("a multisig needs at least one member");
  }

  const seen = new Map<string, number>();
  members.forEach((m, i) => {
    const first = seen.get(m.keyHash);
    if (first !== undefined) {
      problems.push(
        `entries ${first + 1} and ${i + 1} are the same key hash (${m.keyHash.slice(0, 12)}…)` +
          (m.source === "address" ? " — an address and its key hash count once" : ""),
      );
    } else {
      seen.set(m.keyHash, i);
    }
  });
  if (problems.length > 0) {
    throw new MultisigInputError(problems.join("; "));
  }

  if (members.length > MULTISIG_MAX_SIZE) {
    throw new MultisigInputError(
      `${members.length} members exceeds the on-chain limit of ${MULTISIG_MAX_SIZE}`,
    );
  }
  if (!Number.isInteger(required) || required < 1 || required > members.length) {
    throw new MultisigInputError(
      `threshold must be a whole number between 1 and ${members.length}, got ${required}`,
    );
  }

  const tree: MultisigScriptTree = {
    type: "at-least",
    required,
    scripts: members.map((m) => ({ type: "signature", keyHash: m.keyHash }) as const),
  };

  return { members, required, tree, datum: multisigScriptDatum(tree) };
}
