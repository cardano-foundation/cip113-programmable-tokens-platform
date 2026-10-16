"use client";

import { KeyboardEvent, ReactNode, useEffect, useId, useRef, useState } from "react";
import { cn } from "@/lib/utils";

export interface TabItem {
  id: string;
  label: string;
  content: ReactNode;
}

/**
 * A minimal accessible tab strip (WAI-ARIA tabs pattern): arrow keys / Home / End move between
 * tabs, only the selected panel is mounted. With {@code storageKey} the last tab is remembered in
 * this browser; storage can be unavailable (private mode, previews), so every access is guarded.
 */
export function Tabs({ items, storageKey, className }: { items: TabItem[]; storageKey?: string; className?: string }) {
  const baseId = useId();
  const [active, setActive] = useState(items[0]?.id);
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});

  useEffect(() => {
    if (!storageKey) return;
    try {
      const saved = window.localStorage.getItem(storageKey);
      if (saved && items.some((t) => t.id === saved)) setActive(saved);
    } catch { /* storage unavailable: keep the default tab */ }
    // Restore once per key; the item list is static for a page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey]);

  const select = (id: string) => {
    setActive(id);
    if (storageKey) {
      try { window.localStorage.setItem(storageKey, id); } catch { /* not remembered */ }
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    // Leave browser shortcuts (Alt+Arrow = back/forward, etc.) alone.
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    const last = items.length - 1;
    const next = e.key === "ArrowRight" ? (index === last ? 0 : index + 1)
      : e.key === "ArrowLeft" ? (index === 0 ? last : index - 1)
      : e.key === "Home" ? 0 : e.key === "End" ? last : null;
    if (next === null) return;
    e.preventDefault();
    select(items[next].id);
    refs.current[items[next].id]?.focus();
  };

  const current = items.find((t) => t.id === active) ?? items[0];
  return (
    <div className={cn("space-y-4", className)}>
      <div role="tablist" aria-orientation="horizontal"
        className="flex gap-1 border-b border-dark-700">
        {items.map((t, i) => {
          const selected = t.id === current?.id;
          return (
            <button key={t.id} ref={(el) => { refs.current[t.id] = el; }}
              id={`${baseId}-tab-${t.id}`} role="tab" type="button"
              // Only the selected panel is mounted, so only its tab may point at it.
              aria-selected={selected} aria-controls={selected ? `${baseId}-panel-${t.id}` : undefined}
              tabIndex={selected ? 0 : -1}
              onClick={() => select(t.id)} onKeyDown={(e) => onKeyDown(e, i)}
              className={cn(
                "-mb-px border-b-2 px-4 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500 rounded-t",
                selected ? "border-primary-500 text-white" : "border-transparent text-dark-400 hover:text-dark-200",
              )}>
              {t.label}
            </button>
          );
        })}
      </div>
      {current && (
        <div role="tabpanel" id={`${baseId}-panel-${current.id}`} aria-labelledby={`${baseId}-tab-${current.id}`}
          tabIndex={0} className="focus:outline-none">
          {current.content}
        </div>
      )}
    </div>
  );
}
