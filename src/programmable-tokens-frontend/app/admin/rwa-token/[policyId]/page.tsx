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
  listRwaMembers,
  PowerUserCapability,
  type DenylistEntry,
  type PowerUser,
  type PowerUserCapabilityName,
  type RwaMemberCandidate,
  type RwaMemberLeaf,
  type RwaMemberList,
} from "@/lib/api/rwa-token";
import { getTokenContext } from "@/lib/api/protocol";
import { useWallet } from "@/hooks/use-wallet";
import { getPaymentKeyHash } from "@/lib/utils/address";
import { getCardanoNetwork } from "@/lib/utils/network";
import { resolveStakeMemberAddress } from "@/lib/rwa/stake-address";
import { reviewMemberRootTransaction } from "@/lib/rwa/review-root-tx";
import { canonicalMemberBody, findAdminWalletAddress, signRwaAdminRequest } from "@/lib/rwa/admin-auth";

const CAPABILITY_NAMES = Object.keys(PowerUserCapability) as PowerUserCapabilityName[];

export default function RwaTokenAdminPage() {
  const params = useParams<{ policyId: string }>();
  const policyId = params?.policyId ?? "";

  const [moduleOk, setModuleOk] = useState<boolean | null>(null);
  const [requiresReceiverKyc, setRequiresReceiverKyc] = useState<boolean | null>(null);

  useEffect(() => {
    if (!policyId) return;
    getTokenContext(policyId)
      .then((ctx) => {
        if (ctx.moduleId !== "rwa-token") {
          setModuleOk(false);
          return;
        }
        setModuleOk(true);
        setRequiresReceiverKyc(ctx.requiresReceiverKyc ?? null);
      })
      .catch(() => setModuleOk(false));
  }, [policyId]);

  if (moduleOk === null) {
    return (
      <PageContainer>
        <div className="max-w-4xl mx-auto py-10 flex items-center gap-2 text-sm text-dark-300">
          <Loader2 className="h-4 w-4 animate-spin text-primary-400" /> Loading…
        </div>
      </PageContainer>
    );
  }

  if (moduleOk === false) {
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
  const { wallet, rawApi } = useWallet();
  const [state, setState] = useState<Awaited<ReturnType<typeof getRwaTokenGlobalState>> | null>(null);
  const [members, setMembers] = useState<RwaMemberList | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [stakeAddress, setStakeAddress] = useState("");
  const [expiry, setExpiry] = useState("");
  const [candidate, setCandidate] = useState<(RwaMemberCandidate & {
    approvedAdded: RwaMemberLeaf[];
    manualStakeAddress?: string;
  }) | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastPublishedTx, setLastPublishedTx] = useState<string | null>(null);

  const refresh = async () => {
    try {
      const gs = await getRwaTokenGlobalState(policyId);
      setState(gs);
      if (!rawApi) { setMembers(null); return; }
      const headers = await signRwaAdminRequest({
        rawApi, policyId, gsPolicyId: gs.globalStatePolicyId,
        adminHash: gs.adminCredentialHash, method: "GET",
        path: `/rwa-token/${policyId}/members`, canonicalBody: "",
      });
      setMembers(await listRwaMembers(policyId, headers));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  useEffect(() => {
    getRwaTokenGlobalState(policyId).then(setState)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [policyId]);

  const pendingKey = (member: { credentialHash: string; credentialType: number }) =>
    `${member.credentialType}:${member.credentialHash.toLowerCase()}`;

  const handlePrepare = async () => {
    if (!wallet) {
      setError("Connect a wallet first");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (!state) throw new Error("Global State is not loaded");
      const adminAddress = await findAdminWalletAddress(wallet, state.adminCredentialHash);
      if (!state || getPaymentKeyHash(adminAddress).toLowerCase() !== state.adminCredentialHash.toLowerCase())
        throw new Error("The connected wallet is not the current on-chain GS admin");
      let manualMember;
      if (stakeAddress.trim()) {
        const credential = resolveStakeMemberAddress(stakeAddress, getCardanoNetwork());
        const validUntilMs = new Date(expiry).getTime();
        if (!Number.isSafeInteger(validUntilMs) || validUntilMs <= Date.now()) throw new Error("Choose a future expiry");
        manualMember = { ...credential, validUntilMs };
      }
      const selectedPendingMembers = members?.pending.filter((member) => selected.has(pendingKey(member))) ?? [];
      const body = {
        feePayerAddress: adminAddress,
        manualMember,
        selectedPendingMembers,
      };
      const headers = await signRwaAdminRequest({
        rawApi, policyId, gsPolicyId: state.globalStatePolicyId,
        adminHash: state.adminCredentialHash, method: "POST",
        path: `/rwa-token/${policyId}/update-member-root-hash`,
        canonicalBody: canonicalMemberBody(adminAddress, manualMember, selectedPendingMembers),
      });
      const proposal = await buildUpdateMemberRootHashTx(policyId, body, headers);
      const approvedAdded = [
        ...(manualMember ? [manualMember] : []),
        ...selectedPendingMembers,
      ];
      const key = (member: RwaMemberLeaf) => `${member.credentialType}:${member.credentialHash.toLowerCase()}:${member.validUntilMs}`;
      if (approvedAdded.map(key).sort().join("|") !== proposal.added.map(key).sort().join("|"))
        throw new Error("Backend proposal includes an unselected member or changed expiry");
      setCandidate({ ...proposal, approvedAdded,
        manualStakeAddress: manualMember ? stakeAddress.trim() : undefined });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const handleSign = async () => {
    if (!wallet || !candidate || !state) return;
    setBusy(true);
    setError(null);
    try {
      await findAdminWalletAddress(wallet, state.adminCredentialHash);
      await reviewMemberRootTransaction({
        ...candidate,
        gsPolicyId: state.globalStatePolicyId,
        tokenPolicyId: policyId,
        adminHash: state.adminCredentialHash,
      });
      const signed = await wallet.signTx(candidate.unsignedCborTx, true);
      const txHash = await wallet.submitTx(signed);
      if (txHash.toLowerCase() !== candidate.txHash.toLowerCase())
        throw new Error("Wallet submitted a different transaction body; inspect the chain before retrying");
      setLastPublishedTx(txHash);
      setCandidate(null);
      setTimeout(() => {
        getRwaTokenGlobalState(policyId).then(setState)
          .catch((e) => setError(e instanceof Error ? e.message : String(e)));
      }, 15_000);
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
        Add a stake credential to the CMTA member tree. The admin wallet signs
        the root update after you review the exact members being added. A member
        can receive proofs after the transaction is confirmed on chain.
      </p>

      <div className="space-y-1">
        <p className="text-xs text-dark-500 uppercase tracking-wider">Current on-chain</p>
        <p className="text-xs font-mono text-dark-300 break-all">
          {state?.memberRootHash ?? "—"}
        </p>
      </div>

      <Button type="button" variant="secondary" onClick={() => void refresh()} disabled={!rawApi || busy}>
        Load current and pending members
      </Button>

      <div className="space-y-2">
        <label className="block text-sm text-white" htmlFor="manual-stake-address">Stake address</label>
        <Input id="manual-stake-address" value={stakeAddress} onChange={(e) => { setStakeAddress(e.target.value); setCandidate(null); }} placeholder={getCardanoNetwork() === "mainnet" ? "stake1…" : "stake_test1…"} />
        <p className="text-xs text-dark-400">The address determines the stake credential and whether it is a key or script.</p>
        <label className="block text-sm text-white" htmlFor="manual-expiry">Valid until</label>
        <Input id="manual-expiry" type="datetime-local" value={expiry} onChange={(e) => { setExpiry(e.target.value); setCandidate(null); }} />
      </div>

      {members && members.pending.length > 0 && (
        <div className="space-y-2">
          <p className="text-sm text-white">Pending Veridian members and expiry updates (select only those to include)</p>
          {members.pending.map((member) => {
            const key = pendingKey(member);
            return <label key={key} className="flex items-start gap-2 text-xs text-dark-300">
              <input type="checkbox" checked={selected.has(key)} onChange={(e) => {
                const next = new Set(selected); e.target.checked ? next.add(key) : next.delete(key);
                setSelected(next); setCandidate(null);
              }} />
              <span className="font-mono break-all">{member.credentialHash} ({member.credentialType === 0 ? "key" : "script"}; expires {new Date(member.validUntilMs).toLocaleString()})</span>
            </label>;
          })}
        </div>
      )}

      <Button
        type="button"
        variant="primary"
        onClick={handlePrepare}
        disabled={busy}
      >
        {busy ? "Preparing…" : "Review member root update"}
      </Button>

      {candidate && <div className="space-y-3 rounded border border-amber-500/40 bg-amber-500/5 p-3 text-xs text-dark-200">
        <p className="font-semibold text-white">Review before wallet signing</p>
        {candidate.manualStakeAddress && <p className="font-mono break-all">Manual stake address: {candidate.manualStakeAddress}</p>}
        <p>Current members: {candidate.baseline.length}. Added or updated: {candidate.added.length}. New total: {candidate.leaves.length}.</p>
        {candidate.added.map((member) => <p key={pendingKey(member)} className="font-mono break-all">
          {member.credentialHash} ({member.credentialType === 0 ? "stake key" : "stake script"}) — expires {new Date(member.validUntilMs).toLocaleString()}
        </p>)}
        <p className="font-mono break-all">New root: {candidate.newRootHashHex}</p>
        <Button type="button" variant="primary" onClick={handleSign} disabled={busy}>{busy ? "Checking and signing…" : "Verify on chain and sign"}</Button>
      </div>}

      {lastPublishedTx && (
        <p className="text-xs text-green-400 break-all">
          Submitted: {lastPublishedTx}. Waiting for chain confirmation; refresh to see the active root.
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
