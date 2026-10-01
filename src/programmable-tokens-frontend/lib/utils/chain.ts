/**
 * The single Evolution `Chain` for the configured network, devnet included.
 *
 * ⛔ THIS REPLACES TWO DUPLICATE SELECTORS. `chainFor` in lib/deployment/deploy.ts and
 * `getChain` in contexts/cip113-context.tsx each switched over the network and each returned
 * `undefined` for anything they did not list — which is how a devnet build reached the Evolution
 * client with no chain at all. Adding a network meant editing both, and the compiler only caught
 * it because both were exhaustive switches over a union that grew.
 *
 * Kept OUT of lib/utils/network.ts on purpose: that module is compiled standalone by several
 * node test suites (`tsc lib/utils/network.ts --outDir …`), and importing the SDK there would
 * give those suites a runtime dependency on it for the sake of a type they never use.
 */
import { previewChain, preprodChain, mainnetChain } from "@easy1staking/cip113-sdk-ts";
// Relative for the same reason as in deploy.ts: this module is pulled into standalone tsc
// compiles that have no path aliases configured.
import { getCardanoNetwork, getDevnetChainParams, type CardanoNetwork } from "./network";

/** The shape Evolution's `Chain` requires; structural, so no type import is needed here. */
export interface EvolutionChain {
  readonly id: number;
  readonly name: string;
  readonly networkMagic: number;
  readonly epochLength: number;
  readonly slotConfig: { readonly zeroTime: bigint; readonly zeroSlot: bigint; readonly slotLength: number };
}

/**
 * ⚑ A DEVNET CHAIN IS BUILT, NOT LOOKED UP. Evolution ships presets for the three public
 * networks and its own Chain docs invite a custom one for devnets, because the parameters are
 * properties of a cluster that is recreated on demand: `zeroTime` is the genesis systemStart and
 * changes every time. `getDevnetChainParams()` throws, naming the variable, rather than letting
 * a default through — a guessed zeroTime makes the node reject every transaction with
 * `SlotTooFarInThePast`, which says nothing about configuration.
 */
export function getEvolutionChain(network: CardanoNetwork = getCardanoNetwork()): EvolutionChain {
  switch (network) {
    case "mainnet": return mainnetChain as unknown as EvolutionChain;
    case "preprod": return preprodChain as unknown as EvolutionChain;
    case "preview": return previewChain as unknown as EvolutionChain;
    case "devnet": {
      const p = getDevnetChainParams();
      return {
        // Testnet address encoding. A devkit devnet is a testnet whatever its magic.
        id: 0,
        name: `Cardano Devnet (magic ${p.networkMagic})`,
        networkMagic: p.networkMagic,
        epochLength: p.epochLength,
        slotConfig: {
          zeroTime: BigInt(p.zeroTime),
          zeroSlot: BigInt(p.zeroSlot),
          slotLength: p.slotLength,
        },
      };
    }
  }
}
