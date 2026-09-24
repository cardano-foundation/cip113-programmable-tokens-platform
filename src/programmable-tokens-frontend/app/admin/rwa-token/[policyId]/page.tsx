"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { PageContainer } from "@/components/layout/page-container";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Loader2, Plus, Trash2, ShieldCheck, ArrowLeft } from "lucide-react";
import Link from "next/link";
import {
  acknowledgeRootPublish,
  listRwaTokenMembers,
  requestRwaTokenInclusion,
  type RwaTokenMember,
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

  // Bumped by a successful root publish so the member list re-reads its
  // published/pending badges — they are the whole point of this page and are
  // exactly what a publish changes.
  const [publishCount, setPublishCount] = useState(0);
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
        <Link
          href="/admin"
          className="inline-flex items-center gap-1.5 text-xs text-dark-400 transition-colors hover:text-primary-400"
        >
          <ArrowLeft className="h-3.5 w-3.5" /> Back to token administration
        </Link>

        <div className="space-y-1">
          <h1 className="text-3xl font-bold text-white">RWA-Token Admin</h1>
          <p className="text-xs font-mono text-dark-400 break-all">{policyId}</p>
          {requiresReceiverKyc !== null && (
            <Badge variant={requiresReceiverKyc ? "success" : "default"} size="sm">
              requires_receiver_kyc = {String(requiresReceiverKyc)}
            </Badge>
          )}
        </div>

        <MemberRootHashSection
          policyId={policyId}
          onPublished={() => setPublishCount((n) => n + 1)}
        />
        <AllowlistSection policyId={policyId} refreshToken={publishCount} />
        <PowerUsersSection policyId={policyId} />
        <DenylistSection policyId={policyId} />
      </div>
    </PageContainer>
  );
}

// ── Member root hash (admin-signed publish) ─────────────────────────────────

function MemberRootHashSection({
  policyId,
  onPublished,
}: {
  policyId: string;
  onPublished?: () => void;
}) {
  const { wallet } = useWallet();
  const [onchainHash, setOnchainHash] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
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
    setNotice(null);
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

      // Tell the backend the root landed. WITHOUT THIS the publish is invisible
      // off chain: `markLeavesPublished` never runs, every leaf keeps
      // `publishedAt = null`, and GET /proofs/{pkh} answers 425 "publish
      // pending" forever — so a member who was added AND published still reads
      // as "not in the allowlist" at the point of transfer, with a correct root
      // sitting on chain the whole time. That is exactly the failure this
      // section is supposed to resolve, so the ack is not optional here.
      //
      // GlobalStateSection runs the same ack for its UpdateMemberRootHash
      // action; this page is the other way to reach the same operation, and it
      // was the half that did not.
      try {
        const ack = await acknowledgeRootPublish(policyId, {
          txHash,
          newRootHashHex,
        });
        if (ack.rootDrifted) {
          // 200, but nothing was marked. Reported as an error because the member
          // the admin just published for is still pending and will still be
          // refused — the same visible symptom as publishing not working at all.
          setError(
            ack.message ??
              "The allowlist changed while this root was being published, so no " +
                "members were marked on chain. Publish again to cover them."
          );
        } else {
          setNotice(
            `Published. ${ack.leavesMarkedPublished} member` +
              `${ack.leavesMarkedPublished === 1 ? "" : "s"} now marked on chain.`
          );
        }
        onPublished?.();
      } catch (ackErr) {
        // The chain has the root either way, so this is recoverable rather than
        // fatal — but it is NOT a console warning, because the visible symptom
        // is "the allowlist does not work" and the admin is the only one who can
        // retry. Say so where they are looking.
        setError(
          "The root was submitted (" +
            txHash.slice(0, 12) +
            "…) but the backend did not record it, so members will still show as " +
            "pending. Publish again once the node has caught up. " +
            (ackErr instanceof Error ? ackErr.message : String(ackErr))
        );
      }
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
      {notice && <p className="text-xs text-primary-400">{notice}</p>}
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

// ── Allowlist members (off-chain tree; live only once the root is published) ──

const DEFAULT_VALIDITY_DAYS = 365;

function AllowlistSection({
  policyId,
  refreshToken = 0,
}: {
  policyId: string;
  refreshToken?: number;
}) {
  const [members, setMembers] = useState<RwaTokenMember[] | null>(null);
  const [address, setAddress] = useState("");
  const [days, setDays] = useState(String(DEFAULT_VALIDITY_DAYS));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = () => {
    listRwaTokenMembers(policyId)
      .then(setMembers)
      .catch(() => setMembers([]));
  };

  useEffect(refresh, [policyId, refreshToken]);

  const handleAdd = async () => {
    setError(null);
    setNotice(null);
    const addr = address.trim();
    // Guard here rather than letting the backend derive nothing: it identifies a
    // member by the STAKE credential, so an enterprise address has no leaf key at
    // all and comes back as a bare 400. Naming the reason up front is the whole
    // difference between "fix your address" and "the allowlist is broken".
    if (!addr.startsWith("addr")) {
      setError("Enter a bech32 Cardano address (addr… / addr_test…).");
      return;
    }
    const parsedDays = Number(days);
    if (!Number.isFinite(parsedDays) || parsedDays <= 0) {
      setError("Validity must be a positive number of days.");
      return;
    }
    setBusy(true);
    try {
      const res = await requestRwaTokenInclusion(policyId, {
        boundAddress: addr,
        validUntilMs: Date.now() + parsedDays * 24 * 60 * 60 * 1000,
      });
      setAddress("");
      setNotice(
        `Added ${res.memberPkh.slice(0, 12)}… to the off-chain tree. ` +
          `Publish the root below to make it effective on chain.`
      );
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const pendingCount = members?.filter((m) => !m.published).length ?? 0;

  return (
    <Card className="p-6 space-y-4">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-lg font-semibold text-white">Allowlist members</h2>
        {members !== null && (
          <Badge variant="info" size="sm">
            {members.length} member{members.length === 1 ? "" : "s"}
          </Badge>
        )}
      </div>
      <p className="text-xs text-dark-400">
        Who may RECEIVE this token. Adding a member writes the off-chain MPF tree only — it
        takes effect on chain when you publish the root above. Members normally enrol
        themselves by completing KYC; add them here to admit a wallet directly.
      </p>
      {pendingCount > 0 && (
        <p className="text-xs text-accent-400">
          {pendingCount} member{pendingCount === 1 ? "" : "s"} not yet on chain — publish the
          root above, or transfers to them will still be refused.
        </p>
      )}

      <div className="space-y-2">
        {members === null ? (
          <p className="text-sm text-dark-400 flex items-center gap-2">
            <Loader2 className="h-3 w-3 animate-spin" /> Loading…
          </p>
        ) : members.length === 0 ? (
          <p className="text-sm text-dark-400">No members enrolled.</p>
        ) : (
          members.map((m) => (
            // Keyed on BOTH fields: the same hash legitimately has two leaves, one per
            // credential form, and keying on the hash alone drops one of them.
            <div
              key={`${m.memberPkh}:${m.credentialType}`}
              className="flex items-start justify-between gap-3 p-3 bg-dark-900 rounded"
            >
              <div className="min-w-0 space-y-0.5">
                <p className="text-xs font-mono text-dark-300 break-all">{m.memberPkh}</p>
                {m.boundAddress && (
                  <p className="text-[10px] font-mono text-dark-500 break-all">{m.boundAddress}</p>
                )}
                <p className="text-[10px] text-dark-500">
                  {m.credentialType === 1 ? "Script credential" : "Verification key"}
                  {" · expires "}
                  {new Date(m.validUntilMs).toISOString().slice(0, 10)}
                </p>
              </div>
              <div className="flex flex-col items-end gap-1 shrink-0">
                <Badge variant={m.published ? "success" : "warning"} size="sm">
                  {m.published ? "On chain" : "Pending publish"}
                </Badge>
                {m.expired && (
                  <Badge variant="error" size="sm">
                    Expired
                  </Badge>
                )}
              </div>
            </div>
          ))
        )}
      </div>

      <div className="space-y-3 pt-2 border-t border-dark-700">
        <h3 className="text-sm font-semibold text-white">Add member</h3>
        <Input
          placeholder="Wallet address (addr_test1… — must be a base address)"
          value={address}
          onChange={(e) => setAddress(e.target.value)}
          disabled={busy}
        />
        <div className="flex items-center gap-2">
          <Input
            type="number"
            min="1"
            placeholder="Validity (days)"
            value={days}
            onChange={(e) => setDays(e.target.value)}
            disabled={busy}
            className="max-w-[160px]"
          />
          <span className="text-xs text-dark-500">days of membership validity</span>
        </div>
        <Button
          type="button"
          variant="primary"
          onClick={handleAdd}
          disabled={busy || !address.trim()}
        >
          {busy ? (
            <>
              <Loader2 className="h-4 w-4 mr-1 animate-spin" /> Adding…
            </>
          ) : (
            <>
              <Plus className="h-4 w-4 mr-1" /> Add member
            </>
          )}
        </Button>
        <p className="text-[10px] text-dark-500">
          Identity is the wallet&apos;s STAKE credential, so an enterprise address (one with no
          stake part) cannot be enrolled.
        </p>
        {notice && <p className="text-xs text-primary-400">{notice}</p>}
        {error && <p className="text-xs text-red-400">{error}</p>}
      </div>
    </Card>
  );
}
