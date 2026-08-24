"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { PageContainer } from "@/components/layout/page-container";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Loader2, Plus, Trash2, ShieldCheck } from "lucide-react";
import {
  listDenylist,
  addDenylistEntry,
  removeDenylistEntry,
  listPowerUsers,
  addPowerUser,
  removePowerUser,
  addPowerUserOnChain,
  buildUpdateMemberRootHashTx,
  getRwaTokenGlobalState,
  PowerUserCapability,
  type DenylistEntry,
  type PowerUser,
  type PowerUserCapabilityName,
} from "@/lib/api/rwa-token";
import { getTokenContext } from "@/lib/api/protocol";
import { useWallet } from "@/hooks/use-wallet";

const CAPABILITY_NAMES = Object.keys(PowerUserCapability) as PowerUserCapabilityName[];

export default function RwaTokenAdminPage() {
  const params = useParams<{ policyId: string }>();
  const policyId = params?.policyId ?? "";

  const [substandardOk, setSubstandardOk] = useState<boolean | null>(null);
  const [requiresReceiverKyc, setRequiresReceiverKyc] = useState<boolean | null>(null);

  useEffect(() => {
    if (!policyId) return;
    getTokenContext(policyId)
      .then((ctx) => {
        if (ctx.substandardId !== "rwa-token") {
          setSubstandardOk(false);
          return;
        }
        setSubstandardOk(true);
        setRequiresReceiverKyc(ctx.requiresReceiverKyc ?? null);
      })
      .catch(() => setSubstandardOk(false));
  }, [policyId]);

  if (substandardOk === null) {
    return (
      <PageContainer>
        <div className="max-w-4xl mx-auto py-10 flex items-center gap-2 text-sm text-dark-300">
          <Loader2 className="h-4 w-4 animate-spin text-primary-400" /> Loading…
        </div>
      </PageContainer>
    );
  }

  if (substandardOk === false) {
    return (
      <PageContainer>
        <div className="max-w-4xl mx-auto py-10">
          <Card className="p-6 space-y-2">
            <h1 className="text-lg font-semibold text-white">Not a rwa-token</h1>
            <p className="text-sm text-dark-300">
              This policy id is not registered as a rwa-token; admin operations
              are not available here.
            </p>
          </Card>
        </div>
      </PageContainer>
    );
  }

  return (
    <PageContainer>
      <div className="max-w-4xl mx-auto py-10 space-y-6">
        <div className="space-y-1">
          <h1 className="text-3xl font-bold text-white">RWA-Token Admin</h1>
          <p className="text-xs font-mono text-dark-400 break-all">{policyId}</p>
          {requiresReceiverKyc !== null && (
            <Badge variant={requiresReceiverKyc ? "success" : "default"} size="sm">
              requires_receiver_kyc = {String(requiresReceiverKyc)}
            </Badge>
          )}
        </div>

        <MemberRootHashSection policyId={policyId} />
        <PowerUsersSection policyId={policyId} />
        <DenylistSection policyId={policyId} />
      </div>
    </PageContainer>
  );
}

// ── Member root hash (admin-signed publish) ─────────────────────────────────

function MemberRootHashSection({ policyId }: { policyId: string }) {
  const { wallet } = useWallet();
  const [onchainHash, setOnchainHash] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastPublishedTx, setLastPublishedTx] = useState<string | null>(null);

  const refresh = () => {
    getRwaTokenGlobalState(policyId)
      .then((gs) => setOnchainHash(gs.memberRootHash ?? null))
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(refresh, [policyId]);

  const handlePublish = async () => {
    if (!wallet) {
      setError("Connect a wallet first");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const addrs = await wallet.getUsedAddresses();
      const adminAddress = addrs[0];
      if (!adminAddress) throw new Error("no wallet address");
      const { unsignedCborTx, newRootHashHex } =
        await buildUpdateMemberRootHashTx(policyId, adminAddress);
      const signed = await wallet.signTx(unsignedCborTx, true);
      const txHash = await wallet.submitTx(signed);
      setLastPublishedTx(txHash);
      // Optimistic update; reconfirm after ~20s when chain reflects it.
      setOnchainHash(newRootHashHex);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="p-6 space-y-4">
      <h2 className="text-lg font-semibold text-white">Member root hash</h2>
      <p className="text-xs text-dark-400">
        On-chain MPF allowlist root. The backend recomputes it from your enrolled
        members on every KYC change but does NOT publish autonomously — the
        BaFin validator requires the admin wallet to sign the update.
      </p>

      <div className="space-y-1">
        <p className="text-xs text-dark-500 uppercase tracking-wider">Current on-chain</p>
        <p className="text-xs font-mono text-dark-300 break-all">
          {onchainHash ?? "—"}
        </p>
      </div>

      <Button
        type="button"
        variant="primary"
        onClick={handlePublish}
        disabled={busy}
      >
        {busy ? "Publishing…" : "Publish current root"}
      </Button>

      {lastPublishedTx && (
        <p className="text-xs text-green-400 break-all">
          Submitted: {lastPublishedTx}
        </p>
      )}
      {error && <p className="text-xs text-red-400">{error}</p>}
    </Card>
  );
}

// ── Power users ─────────────────────────────────────────────────────────────

function PowerUsersSection({ policyId }: { policyId: string }) {
  const { wallet } = useWallet();
  const [users, setUsers] = useState<PowerUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pkh, setPkh] = useState("");
  const [label, setLabel] = useState("");
  const [selectedCaps, setSelectedCaps] = useState<Set<PowerUserCapabilityName>>(new Set());
  const [busy, setBusy] = useState(false);
  const [syncingPkh, setSyncingPkh] = useState<string | null>(null);

  const handleSyncToChain = async (target: PowerUser) => {
    if (!wallet) {
      setError("Connect a wallet first");
      return;
    }
    setSyncingPkh(target.powerUserPkh);
    setError(null);
    try {
      const addrs = await wallet.getUsedAddresses();
      const adminAddress = addrs[0];
      if (!adminAddress) throw new Error("no wallet address");
      const resp = await addPowerUserOnChain(policyId, {
        powerUserPkh: target.powerUserPkh,
        capabilities: target.capabilities,
        adminAddress,
      });
      const signed = await wallet.signTx(resp.unsignedCborTx, true);
      await wallet.submitTx(signed);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncingPkh(null);
    }
  };

  const refresh = () => {
    listPowerUsers(policyId)
      .then(setUsers)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(refresh, [policyId]);

  const toggleCap = (cap: PowerUserCapabilityName) => {
    setSelectedCaps((prev) => {
      const next = new Set(prev);
      if (next.has(cap)) next.delete(cap);
      else next.add(cap);
      return next;
    });
  };

  const handleAdd = async () => {
    if (!pkh.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const capsInt = [...selectedCaps].reduce((acc, c) => acc | PowerUserCapability[c], 0);
      await addPowerUser(policyId, {
        powerUserPkh: pkh.trim(),
        capabilities: capsInt,
        label: label.trim() || undefined,
      });
      setPkh("");
      setLabel("");
      setSelectedCaps(new Set());
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const handleRemove = async (powerUserPkh: string) => {
    setBusy(true);
    try {
      await removePowerUser(policyId, powerUserPkh);
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="p-6 space-y-4">
      <div className="flex items-center gap-2">
        <ShieldCheck className="h-5 w-5 text-primary-400" />
        <h2 className="text-lg font-semibold text-white">Power Users</h2>
      </div>
      <p className="text-xs text-dark-400">
        Capabilities are bit-flags. ForceTransfer is reserved (admin seizure is deferred from v1).
      </p>

      <div className="space-y-2">
        {users === null ? (
          <p className="text-sm text-dark-400 flex items-center gap-2">
            <Loader2 className="h-3 w-3 animate-spin" /> Loading…
          </p>
        ) : users.length === 0 ? (
          <p className="text-sm text-dark-400">No power users yet.</p>
        ) : (
          users.map((u) => (
            <div key={u.powerUserPkh} className="flex items-center justify-between gap-2 p-3 bg-dark-900 rounded">
              <div className="min-w-0">
                {u.label && <p className="text-sm text-white truncate">{u.label}</p>}
                <p className="text-xs font-mono text-dark-400 break-all">{u.powerUserPkh}</p>
                <p className="text-xs text-dark-500 mt-0.5">
                  {describeCapabilities(u.capabilities)}
                </p>
              </div>
              <div className="flex items-center gap-1">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => handleSyncToChain(u)}
                  disabled={busy || syncingPkh === u.powerUserPkh}
                  title="Insert this off-chain entry into the on-chain power-users linked list"
                >
                  {syncingPkh === u.powerUserPkh ? "Building & signing…" : "Sync to chain"}
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => handleRemove(u.powerUserPkh)}
                  disabled={busy}
                  title="Remove"
                >
                  <Trash2 className="h-4 w-4 text-red-400" />
                </Button>
              </div>
            </div>
          ))
        )}
      </div>

      <div className="space-y-3 pt-2 border-t border-dark-700">
        <h3 className="text-sm font-semibold text-white">Add power user</h3>
        <Input
          placeholder="Power user PKH (28-byte hex)"
          value={pkh}
          onChange={(e) => setPkh(e.target.value)}
          disabled={busy}
        />
        <Input
          placeholder="Label (optional)"
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          disabled={busy}
        />
        <div className="flex flex-wrap gap-2">
          {CAPABILITY_NAMES.map((cap) => (
            <button
              key={cap}
              type="button"
              onClick={() => toggleCap(cap)}
              disabled={busy}
              className={`px-2 py-1 rounded text-xs border transition-colors ${
                selectedCaps.has(cap)
                  ? "bg-primary-500/20 border-primary-400 text-primary-300"
                  : "border-dark-600 text-dark-400 hover:text-white"
              }`}
            >
              {cap}
            </button>
          ))}
        </div>
        <Button type="button" variant="primary" onClick={handleAdd} disabled={busy || !pkh.trim()}>
          <Plus className="h-4 w-4 mr-1" /> Add
        </Button>
        {error && <p className="text-xs text-red-400">{error}</p>}
      </div>
    </Card>
  );
}

function describeCapabilities(bitfield: number): string {
  const names = CAPABILITY_NAMES.filter((cap) => (bitfield & PowerUserCapability[cap]) !== 0);
  return names.length > 0 ? names.join(", ") : "(none)";
}

// ── Denylist ────────────────────────────────────────────────────────────────

function DenylistSection({ policyId }: { policyId: string }) {
  const [entries, setEntries] = useState<DenylistEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pkh, setPkh] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = () => {
    listDenylist(policyId)
      .then(setEntries)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  useEffect(refresh, [policyId]);

  const handleAdd = async () => {
    if (!pkh.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await addDenylistEntry(policyId, {
        memberPkh: pkh.trim(),
        reason: reason.trim() || undefined,
      });
      setPkh("");
      setReason("");
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const handleRemove = async (memberPkh: string) => {
    setBusy(true);
    try {
      await removeDenylistEntry(policyId, memberPkh);
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="p-6 space-y-4">
      <h2 className="text-lg font-semibold text-white">Denylist</h2>
      <p className="text-xs text-dark-400">
        Off-chain mirror of the on-chain denylist. Use the Admin → Blacklist section to add or remove entries on chain.
      </p>

      <div className="space-y-2">
        {entries === null ? (
          <p className="text-sm text-dark-400 flex items-center gap-2">
            <Loader2 className="h-3 w-3 animate-spin" /> Loading…
          </p>
        ) : entries.length === 0 ? (
          <p className="text-sm text-dark-400">No denylisted members.</p>
        ) : (
          entries.map((e) => (
            <div key={e.memberPkh} className="flex items-center justify-between gap-2 p-3 bg-dark-900 rounded">
              <div className="min-w-0">
                <p className="text-xs font-mono text-dark-400 break-all">{e.memberPkh}</p>
                {e.reason && <p className="text-xs text-dark-500 mt-0.5">{e.reason}</p>}
              </div>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => handleRemove(e.memberPkh)}
                disabled={busy}
                title="Remove"
              >
                <Trash2 className="h-4 w-4 text-red-400" />
              </Button>
            </div>
          ))
        )}
      </div>

      <div className="space-y-3 pt-2 border-t border-dark-700">
        <h3 className="text-sm font-semibold text-white">Add to denylist</h3>
        <Input
          placeholder="Member PKH (28-byte hex)"
          value={pkh}
          onChange={(e) => setPkh(e.target.value)}
          disabled={busy}
        />
        <Input
          placeholder="Reason (optional)"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={busy}
        />
        <Button type="button" variant="primary" onClick={handleAdd} disabled={busy || !pkh.trim()}>
          <Plus className="h-4 w-4 mr-1" /> Add
        </Button>
        {error && <p className="text-xs text-red-400">{error}</p>}
      </div>
    </Card>
  );
}
