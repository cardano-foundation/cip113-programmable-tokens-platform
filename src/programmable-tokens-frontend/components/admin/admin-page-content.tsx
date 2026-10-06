"use client";

import { useEffect, useState } from "react";
import { useWallet } from "@/hooks/use-wallet";
import dynamicImport from "next/dynamic";
import { PageContainer } from "@/components/layout";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Shield, Wallet } from "lucide-react";
import { getAdminTokens, AdminTokenInfo, extractPkhFromAddress } from "@/lib/api/admin";
import { getCardanoNetwork } from "@/lib/utils/network";
import { isModuleAllowedOnNetwork } from "@/lib/registry/available-modules";

const AdminPanelDynamic = dynamicImport(
  () => import("@/components/admin").then((mod) => ({ default: mod.AdminPanel })),
  {
    ssr: false,
    loading: () => (
      <Card>
        <CardContent className="flex items-center justify-center py-16">
          <div className="h-8 w-8 border-4 border-primary-500 border-t-transparent rounded-full animate-spin" />
        </CardContent>
      </Card>
    ),
  }
);

const ConnectButtonDynamic = dynamicImport(
  () => import("@/components/wallet").then((mod) => ({ default: mod.ConnectButton })),
  { ssr: false }
);

export default function AdminPageContent() {
  const { connected, wallet } = useWallet();
  const [address, setAddress] = useState<string>("");
  const [adminTokens, setAdminTokens] = useState<AdminTokenInfo[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    async function loadAdminData() {
      if (!connected || !wallet) {
        setIsLoading(false);
        return;
      }

      try {
        setIsLoading(true);
        const addresses = await wallet.getUsedAddresses();
        if (addresses && addresses.length > 0) {
          const addr = addresses[0];
          setAddress(addr);

          // Extract PKH and fetch admin tokens
          const pkh = await extractPkhFromAddress(addr);
          if (pkh) {
            const response = await getAdminTokens(pkh);
            // ⛔ "EVERYTHING ELSE MUST BE HIDDEN" COVERS OPERATING A TOKEN, NOT ONLY REGISTERING
            // ONE. Giovanni, 2026-10-06. The registration gate gates the WIZARD; this gates the
            // admin surface, and an adversarial review found the gap between them: a mainnet
            // `dummy` or `freeze-and-seize` token — reachable without this frontend, via the SDK
            // or any other client, because the chain does not honour a build-time allowlist —
            // would still be minted, burned, seized and denylisted from /admin.
            //
            // ⚠ AND `dummy` MINTING SKIPS THE ROLE CHECK BY DESIGN. MintSection treats
            // `moduleId === "dummy"` as open mint, so on mainnet any connected wallet could mint
            // such a token. Filtering here is one gate that covers Mint, Burn, Blacklist, Seize
            // and Global State at once, rather than five independent ones.
            const network = getCardanoNetwork();
            setAdminTokens(
              response.tokens.filter((token) =>
                isModuleAllowedOnNetwork(network, token.moduleId ?? "")
              )
            );
          }
        }
      } catch (error) {
        console.error("Failed to load admin data:", error);
        setAdminTokens([]);
      } finally {
        setIsLoading(false);
      }
    }

    loadAdminData();
  }, [connected, wallet]);

  return (
    <PageContainer maxWidth="lg">
      <div className="space-y-8">
        {/* Header */}
        <div className="text-center space-y-2">
          <h1 className="text-4xl font-bold text-white">Admin Panel</h1>
          <p className="text-dark-300">
            Manage your programmable token administration tasks
          </p>
        </div>

        {/* Content */}
        {!connected ? (
          <Card>
            <CardContent className="flex flex-col items-center py-16">
              <Wallet className="h-16 w-16 text-dark-600 mb-4" />
              <h3 className="text-xl font-semibold text-white mb-2">
                Connect Your Wallet
              </h3>
              <p className="text-dark-400 text-center max-w-md mb-6">
                Connect your wallet to access token administration features.
                Only wallets with admin permissions will see available actions.
              </p>
              <ConnectButtonDynamic />
            </CardContent>
          </Card>
        ) : isLoading ? (
          <Card>
            <CardContent className="flex flex-col items-center py-16">
              <div className="h-12 w-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin mb-4" />
              <p className="text-dark-400">Loading admin permissions...</p>
            </CardContent>
          </Card>
        ) : adminTokens.length === 0 ? (
          <Card>
            <CardContent className="flex flex-col items-center py-16">
              <Shield className="h-16 w-16 text-dark-600 mb-4" />
              <h3 className="text-xl font-semibold text-white mb-2">
                No Admin Access
              </h3>
              <p className="text-dark-400 text-center max-w-md mb-6">
                Your connected wallet doesn&apos;t have administrator permissions
                for any programmable tokens.
              </p>
              <div className="flex gap-4">
                <Button variant="ghost" onClick={() => window.location.href = "/"}>
                  Go Home
                </Button>
                <Button variant="primary" onClick={() => window.location.href = "/register"}>
                  Register a Token
                </Button>
              </div>
            </CardContent>
          </Card>
        ) : (
          <AdminPanelDynamic tokens={adminTokens} adminAddress={address} />
        )}
      </div>
    </PageContainer>
  );
}
