"use client";

/**
 * Hidden page: check the miner on THIS device.
 *
 * Unlisted, like the other /ops pages, and read-only — it builds a transaction body in memory,
 * never touches a wallet and never submits anything.
 *
 * It exists because every wall-clock estimate the miner shows comes from a rate measured on the
 * machine doing the work, and the only way to know what a phone or a laptop actually manages is
 * to run it there. The reference figure is ~28,000 hashes/second over a 2 KB body on a developer
 * workstation; a mid-range phone may be several times slower, and that is the difference between
 * "a few seconds" and "long enough to put the tab in the background and lose it".
 *
 * The body is assembled as canonical CBOR and then round-tripped through Evolution's own
 * TransactionBody codec, so what is mined here is byte-for-byte the encoding the application
 * produces — not a plausible-looking buffer. That is the same construction the offline tests use,
 * and it is what makes this a check rather than a demonstration.
 */
import { useMemo, useState } from "react";
import { Address, TransactionBody } from "@evolution-sdk/evolution";
import { MiningPanel } from "@/components/mining/mining-panel";
import { locateMiningSlots } from "@/lib/mining/locate";

/** A Preview address, used only to give the sample body a well-formed output. */
const SAMPLE_ADDRESS =
  "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y";

const SELF_LOVELACE = 1_000_000;
const CHANGE_LOVELACE = 50_000_000;

function buildSampleBody(paddingBytes: number): Uint8Array {
  const addressHex = toHex(Address.toBytes(Address.fromBech32(SAMPLE_ADDRESS)));
  const output = (coinHex: string) => `825839${addressHex}${coinHex}`;
  // Metadata-sized padding, so the body can be grown to the size a real registration reaches —
  // hashing cost scales with body size and that is the lever that matters most.
  const padding = paddingBytes > 0 ? `075820${"11".repeat(32)}` : "";
  const hex =
    "a" + (padding ? "4" : "3") +
    "00" + "81" + "82" + "5820" + "ab".repeat(32) + "00" +
    "01" + "82" + output("1a000f4240") + output("1a02faf080") +
    "02" + "1a0002bf20" + padding;
  const parsed = TransactionBody.fromCBORBytes(fromHex(hex));
  return TransactionBody.toCBORBytes(parsed);
}

const toHex = (b: Uint8Array) =>
  Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
const fromHex = (h: string) =>
  Uint8Array.from((h.match(/.{1,2}/g) ?? []).map((x) => parseInt(x, 16)));

export default function MineCheckPage() {
  const [error, setError] = useState<string | null>(null);

  const prepared = useMemo(() => {
    try {
      const body = buildSampleBody(0);
      const addressHex = toHex(Address.toBytes(Address.fromBech32(SAMPLE_ADDRESS)));
      const slots = locateMiningSlots(body, {
        selfAddressHex: addressHex,
        expectedGainsLovelace: SELF_LOVELACE,
        expectedLosesLovelace: CHANGE_LOVELACE,
      });
      return { body, slots };
    } catch (e) {
      setError((e as Error).message);
      return null;
    }
  }, []);

  return (
    <main className="mx-auto max-w-4xl space-y-5 px-4 py-8">
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold text-white">Miner check</h1>
        <p className="max-w-2xl text-sm text-dark-400">
          Measures what this device can do and lets you watch a real search. Nothing here touches a
          wallet and nothing is submitted — the transaction body exists only in this tab.
        </p>
      </header>

      {error && (
        <p className="rounded border border-red-800 bg-red-950/25 p-3 text-xs text-red-200">{error}</p>
      )}

      {prepared && (
        <>
          <p className="text-xs text-dark-400">
            Sample body: {prepared.body.length} bytes, two outputs at one address —{" "}
            {(SELF_LOVELACE / 1_000_000).toFixed(0)} ADA to increment and{" "}
            {(CHANGE_LOVELACE / 1_000_000).toFixed(0)} ADA of change to take from. Assembled as
            canonical CBOR and round-tripped through Evolution&apos;s own codec, so it is the
            encoding this application actually produces.
          </p>
          <MiningPanel
            body={prepared.body}
            gains={prepared.slots.gains}
            loses={prepared.slots.loses}
            minUtxoLovelace={1_000_000}
          />
          <p className="text-xs text-dark-500">
            A real registration body is larger than this sample, and hashing cost scales with size —
            roughly 101,000 hashes/second at 512 bytes against 7,100 at 8 KB on the reference
            machine. Treat the rate here as an upper bound for this device.
          </p>
        </>
      )}
    </main>
  );
}
