"use client";

/**
 * One step of the bootstrap, which folds to a single line once it is satisfied.
 *
 * ## The problem
 *
 * The page is a long single column. By phase two the operator is acting at the BOTTOM — a gate
 * countdown, a signature panel, a submit button — while everything the page had to say sat at the
 * TOP: what the seeds were, what verified, what was submitted. Giovanni, 2026-09-29: having to
 * scroll up mid-ceremony to read the answer to the thing you just did. So a finished step collapses
 * to its own one-line answer and the live step is always the lowest thing on the page.
 *
 * ## ⛔ COLLAPSE IS CSS, NEVER CONDITIONAL RENDERING
 *
 * This is the whole reason this is a component rather than three lines of `{done ? … : …}`.
 * Children stay MOUNTED when collapsed and are hidden with `hidden`, because unmounting them
 * destroys their state — and that already happened once here: the note recording WHICH funding
 * strategy built the genesis lived inside a block gated on `!genesisStep`, so it unmounted at the
 * instant the build succeeded, destroying the answer exactly when it became one. The operator was
 * left asking which attempt had worked, which is the entire question the note existed to settle.
 *
 * A collapsed step therefore keeps every input value, every fetched result and every message. The
 * summary is an additional line, not a replacement for the contents.
 *
 * ## No auto-scroll
 *
 * Giovanni's choice between collapse-only and collapse-plus-auto-scroll. A page that scrolls
 * itself fights a reader who has deliberately opened a finished step to check something, and the
 * collapsing already removes the distance that made scrolling necessary.
 */

import { useState, type ReactNode } from "react";

export function CeremonyStep({
  label,
  title,
  done,
  summary,
  children,
}: {
  /** "1", "2", "Phase one" — whatever the operator calls this step. */
  label: string;
  title: string;
  /** Satisfied: fold to one line unless the operator opens it. */
  done: boolean;
  /**
   * The step's ANSWER, in a few words — "3 UTxOs from wallet", "2-of-2", "34 hashes verified".
   * This is what a collapsed step is worth; a summary that only repeats the title wastes the row.
   */
  summary?: ReactNode;
  children: ReactNode;
}) {
  const [openedByHand, setOpenedByHand] = useState(false);
  const collapsed = done && !openedByHand;

  return (
    <section
      className={`space-y-3 rounded border ${
        collapsed ? "border-dark-800 bg-dark-950/60 p-3" : "border-dark-700 p-4"
      }`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2
          className={`flex items-center gap-2 font-semibold text-white ${
            collapsed ? "text-sm" : "text-lg"
          }`}
        >
          {done && <span aria-hidden className="text-green-400">✓</span>}
          <span className={done ? "text-dark-300" : undefined}>
            {label}. {title}
          </span>
          {collapsed && summary && (
            <span className="font-normal text-dark-400">— {summary}</span>
          )}
        </h2>
        {done && (
          <button
            type="button"
            onClick={() => setOpenedByHand((v) => !v)}
            className="rounded border border-dark-700 px-2 py-0.5 text-[10px] text-dark-300 hover:text-primary-400"
          >
            {collapsed ? "details" : "hide"}
          </button>
        )}
      </div>
      {/*
        `hidden`, not `{!collapsed && …}`. See the note at the top of this file: unmounting a
        finished step throws away the answer it is holding.
      */}
      <div hidden={collapsed} className="space-y-3">
        {children}
      </div>
    </section>
  );
}
