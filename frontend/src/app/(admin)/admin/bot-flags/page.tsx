"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle, ShieldAlert, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { clearBotFlag, getBotFlags } from "@/lib/api/bot-flags";
import { getErrorMessage } from "@/lib/api/client";
import type { BotFlag, BotFlagStatus } from "@/types/bot-flags";

const FILTERS: Array<{ value: BotFlagStatus | "ALL"; label: string }> = [
  { value: "ACTIVE", label: "Blocked" },
  { value: "CLEARED", label: "Cleared" },
  { value: "ALL", label: "All" },
];

// Feature chính để ADMIN đọc nhanh vì sao bị gắn cờ; đủ danh sách nằm trong chi tiết
const KEY_FEATURES: Array<{ key: string; label: string }> = [
  { key: "buyer_adds_1m", label: "Adds / min" },
  { key: "device_accounts_24h", label: "Accounts on device" },
  { key: "secs_since_view", label: "Secs after viewing" },
  { key: "account_age_hours", label: "Account age (h)" },
];

const dateTime = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short" });

function shortId(id: string | null) {
  return id ? id.slice(0, 8) : "—";
}

function featureValue(flag: BotFlag, key: string) {
  const value = flag.features[key];
  if (value === null || value === undefined) return "—";
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

export default function AdminBotFlagsPage() {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<BotFlagStatus | "ALL">("ACTIVE");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<BotFlag | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const flagsQuery = useQuery({
    queryKey: ["admin", "bot-flags", status, page],
    queryFn: () => getBotFlags(status, page),
  });
  const clearMutation = useMutation({
    mutationFn: (flagId: string) => clearBotFlag(flagId),
    onSuccess: async (flag) => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "bot-flags"] });
      setSelected(null);
      setNotice(`Buyer ${shortId(flag.buyerId)} can add tickets again.`);
    },
  });

  function closeDialog(open: boolean) {
    if (!open) {
      setSelected(null);
      clearMutation.reset();
    }
  }

  const data = flagsQuery.data;

  return (
    <div className="mx-auto w-full max-w-7xl space-y-6">
      <div>
        <h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">Bot review</h1>
        <p className="mt-2 max-w-3xl text-sm text-muted-foreground">
          Buyers and devices that bot detection scored as high risk. They cannot add more tickets until
          you clear the flag; tickets they already hold are not affected. Scores come from a model trained
          on generated traffic, so check the signals before deciding.
        </p>
      </div>

      {notice ? (
        <p
          role="status"
          aria-live="polite"
          className="rounded-lg border border-primary/20 bg-primary/5 px-4 py-3 text-sm font-medium text-foreground"
        >
          {notice}
        </p>
      ) : null}

      <section
        className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm"
        aria-labelledby="bot-flag-list-title"
      >
        <div className="flex flex-col gap-4 border-b border-border p-4 sm:flex-row sm:items-center sm:justify-between md:p-5">
          <div>
            <h2 id="bot-flag-list-title" className="font-heading text-lg font-semibold">
              Flags
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              {data ? `${data.totalElements} flags` : "Loading flags"}
            </p>
          </div>
          <div role="group" aria-label="Filter by status" className="inline-flex rounded-lg border border-border p-1">
            {FILTERS.map((filter) => (
              <button
                key={filter.value}
                type="button"
                aria-pressed={status === filter.value}
                onClick={() => {
                  setStatus(filter.value);
                  setPage(0);
                  setNotice(null);
                }}
                className={`min-h-9 rounded-md px-3 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                  status === filter.value ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:bg-muted"
                }`}
              >
                {filter.label}
              </button>
            ))}
          </div>
        </div>

        {flagsQuery.isPending ? (
          <p role="status" className="flex items-center gap-2 p-6 text-sm text-muted-foreground">
            <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
            Loading flags…
          </p>
        ) : null}
        {flagsQuery.isError ? (
          <p role="alert" className="p-6 text-sm text-destructive">
            {getErrorMessage(flagsQuery.error, "Could not load bot flags.")}
          </p>
        ) : null}
        {data && data.content.length === 0 ? (
          <div className="flex flex-col items-center gap-2 p-10 text-center">
            <ShieldCheck aria-hidden="true" className="size-8 text-primary" />
            <p className="font-semibold">No flags here</p>
            <p className="text-sm text-muted-foreground">Nothing matches this filter.</p>
          </div>
        ) : null}

        {data && data.content.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full min-w-200 text-left text-sm">
              <thead className="bg-muted/60 text-xs uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th scope="col" className="px-4 py-3 font-semibold">Buyer</th>
                  <th scope="col" className="px-4 py-3 font-semibold">Score</th>
                  {KEY_FEATURES.map((feature) => (
                    <th key={feature.key} scope="col" className="px-4 py-3 font-semibold">{feature.label}</th>
                  ))}
                  <th scope="col" className="px-4 py-3 font-semibold">Hits</th>
                  <th scope="col" className="px-4 py-3 font-semibold">Last flagged</th>
                  <th scope="col" className="px-4 py-3 font-semibold"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {data.content.map((flag) => (
                  <tr key={flag.id}>
                    <td className="px-4 py-3">
                      <p className="font-mono text-xs">{shortId(flag.buyerId)}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        Device {flag.deviceHash ? shortId(flag.deviceHash) : "not sent"}
                      </p>
                    </td>
                    <td className="px-4 py-3 font-semibold tabular-nums">{flag.score.toFixed(3)}</td>
                    {KEY_FEATURES.map((feature) => (
                      <td key={feature.key} className="px-4 py-3 tabular-nums">{featureValue(flag, feature.key)}</td>
                    ))}
                    <td className="px-4 py-3 tabular-nums">{flag.hitCount}</td>
                    <td className="px-4 py-3 text-muted-foreground">{dateTime.format(new Date(flag.lastFlaggedAt))}</td>
                    <td className="px-4 py-3 text-right">
                      {flag.status === "ACTIVE" ? (
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => {
                            setNotice(null);
                            setSelected(flag);
                          }}
                        >
                          Review
                        </Button>
                      ) : (
                        <span className="text-xs text-muted-foreground">
                          Cleared {flag.clearedAt ? dateTime.format(new Date(flag.clearedAt)) : ""}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}

        {data && data.totalPages > 1 ? (
          <div className="flex items-center justify-between border-t border-border p-4 text-sm">
            <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              Previous
            </Button>
            <span className="text-muted-foreground">
              Page {data.page + 1} of {data.totalPages}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        ) : null}
      </section>

      <Dialog open={selected !== null} onOpenChange={closeDialog}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <ShieldAlert aria-hidden="true" className="size-5 text-destructive" />
              Review flag
            </DialogTitle>
            <DialogDescription>
              Buyer {shortId(selected?.buyerId ?? null)} · score {selected?.score.toFixed(3)} · model{" "}
              {selected?.modelVersion}. Clearing lets this buyer and device add tickets again.
            </DialogDescription>
          </DialogHeader>
          {selected ? (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg bg-muted/60 p-4 text-sm">
              {Object.entries(selected.features).map(([key, value]) => (
                <div key={key} className="contents">
                  <dt className="text-muted-foreground">{key}</dt>
                  <dd className="text-right font-mono tabular-nums">{value ?? "—"}</dd>
                </div>
              ))}
            </dl>
          ) : null}
          {clearMutation.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {getErrorMessage(clearMutation.error, "Could not clear this flag.")}
            </p>
          ) : null}
          <DialogFooter>
            <Button variant="outline" onClick={() => closeDialog(false)}>
              Keep blocked
            </Button>
            <Button
              onClick={() => selected && clearMutation.mutate(selected.id)}
              disabled={clearMutation.isPending}
            >
              {clearMutation.isPending ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : null}
              Clear flag
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
