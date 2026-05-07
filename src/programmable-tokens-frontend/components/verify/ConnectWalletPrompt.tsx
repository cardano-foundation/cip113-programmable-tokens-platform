"use client";

import dynamic from "next/dynamic";
import { Card } from "@/components/ui/card";
import { Wallet } from "lucide-react";

const ConnectButton = dynamic(
  () => import("@/components/wallet").then((mod) => ({ default: mod.ConnectButton })),
  { ssr: false },
);

export function ConnectWalletPrompt() {
  return (
    <Card className="p-8 flex flex-col items-center text-center gap-4">
      <Wallet className="h-10 w-10 text-primary-400" />
      <div className="space-y-2">
        <h3 className="text-lg font-semibold text-white">Connect your wallet</h3>
        <p className="text-sm text-dark-300 max-w-sm">
          To verify yourself for this token, we need the wallet address that should
          be added to the on-chain allowlist.
        </p>
      </div>
      <ConnectButton />
    </Card>
  );
}
