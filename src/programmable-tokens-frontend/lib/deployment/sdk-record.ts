/**
 * The same deployment, in the shape the SDK's own harness records.
 *
 * One deployment produces two files. They are NOT two formats of different
 * data: measured against the live preview instance, which both repositories
 * record, the SDK's record is this platform's record entry minus the array
 * envelope and minus `schemaVersion`. The other twenty keys are identical in
 * name AND in order.
 *
 * ## The platform shape is kept as the primary one
 *
 * The envelope plus `schemaVersion` is the more defensible format — a file that
 * can hold several instances and says which schema it is — and the SDK says so
 * itself. So this derives the SDK shape from ours rather than the reverse, and
 * nothing here changes what the platform emits.
 *
 * ## Byte-equality is the requirement, so the serialiser is copied exactly
 *
 * `saveInstance` in the SDK's `test/harness/instances.mjs` writes:
 *
 *     JSON.stringify(deployment, (_k, v) => (typeof v === "bigint" ? v.toString() : v), 2) + "\n"
 *
 * Three details carry bytes and would each be lost to a prose summary of that
 * line: the two-space indent, the bigint replacer, and the TRAILING NEWLINE.
 * The platform's own downloads have no trailing newline, so that one is a real
 * difference rather than a formality.
 *
 * ⚑ AND THERE IS NO "ENCODING CHOICE" TO MAKE, which is worth stating because
 * it looks like there is. The replacer converts values whose `typeof` is
 * `"bigint"` and nothing else, so a JavaScript number stays a number. The
 * committed alpha.4 record carrying `maxInlineDatumBytes` as the NUMBER 1024 is
 * therefore not an exception to a string-for-bigint rule — it is that same
 * function, given a number. Copy the function and the encoding follows from
 * each value's runtime type, for any input. Choosing an encoding independently
 * would be choosing to disagree with the SDK for some inputs.
 *
 * ⚠ A consumer reading these back must use BigInt, never Number: a value that
 * WAS a bigint arrives as a decimal string, and `Number("...")` on a large one
 * loses precision silently.
 */

/** `instancePath`'s rule in the SDK harness — the name becomes a filename. */
export const SDK_INSTANCE_NAME_RE = /^[a-z0-9][a-z0-9._-]*$/i;

/** The networks the SDK's instance convention covers. Mainnet is deliberately
 *  absent from it; that is a fact about the convention, not a guard. */
export const SDK_INSTANCE_NETWORKS = ["preview", "devnet"] as const;
export type SdkInstanceNetwork = (typeof SDK_INSTANCE_NETWORKS)[number];

export function isSdkInstanceName(name: string): boolean {
  return SDK_INSTANCE_NAME_RE.test(name);
}

/** Where the SDK expects this file to live, relative to its repository root. */
export function sdkInstancePath(network: string, name: string): string {
  return `deployments/${network}/${name}.json`;
}

/**
 * Our record entry -> the SDK's record.
 *
 * Takes ONE entry, not the array: the caller chooses which instance, because a
 * platform file may hold several and picking for them would be picking silently.
 *
 * Key order is preserved by construction — object rest keeps insertion order for
 * string keys, and `schemaVersion` leads our entry — so this does not re-order
 * and must not be rewritten to build a fresh object field by field.
 */
export function toSdkInstanceRecord(
  entry: Record<string, unknown>,
): Record<string, unknown> {
  if (!entry || typeof entry !== "object" || Array.isArray(entry)) {
    throw new Error(
      "expected ONE bootstrap record entry, not the array — pick the instance first",
    );
  }
  if (!("txHash" in entry)) {
    throw new Error("this is not a bootstrap record: no txHash");
  }
  const { schemaVersion: _schemaVersion, ...rest } = entry as {
    schemaVersion?: unknown;
  } & Record<string, unknown>;
  return rest;
}

/**
 * The exact bytes `saveInstance` would write for this record.
 *
 * Reproduced rather than approximated: the output of this function is compared
 * byte-for-byte against the SDK's own committed instance file in the tests, so
 * any drift in indent, replacer or trailing newline fails there.
 */
export function serialiseSdkInstance(record: Record<string, unknown>): string {
  return (
    JSON.stringify(record, (_k, v) => (typeof v === "bigint" ? v.toString() : v), 2) +
    "\n"
  );
}
