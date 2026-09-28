"use client";

/**
 * Functional-storage notice.
 *
 * ⛔ THIS IS A NOTICE, NOT A CONSENT GATE, and the difference is the whole design. There is no
 * analytics, advertising or tracking to consent to — the site asserts exactly that, in writing,
 * on /legal#privacy. What the banner does is TELL you that functional storage is in use, which
 * is what the supplied copy says. So it never blocks interaction, never darkens the page, and
 * has no "reject" path: rejecting functional storage would mean rejecting the application.
 *
 * Dressing it up as a consent dialogue would be worse than useless — it would imply a choice
 * that does not exist and train people to dismiss a box that never mattered.
 */

import { useEffect, useState } from "react";
import Link from "next/link";

const STORAGE_KEY = "cip113_functional_storage_ack";

export function CookieBanner() {
  // Starts hidden and is revealed by an effect, never the reverse. Rendering it first and
  // hiding it once localStorage is read would flash the banner at every returning visitor on
  // every page load — and server-rendered HTML cannot know what the browser has stored.
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    try {
      if (window.localStorage.getItem(STORAGE_KEY) !== "1") setVisible(true);
    } catch {
      // Storage can throw — private windows, blocked site data. A banner that cannot record
      // its own dismissal would reappear forever, which is more annoying than not showing it.
      // The notice is informational, so failing closed costs nothing.
    }
  }, []);

  if (!visible) return null;

  const acknowledge = () => {
    try {
      window.localStorage.setItem(STORAGE_KEY, "1");
    } catch {
      /* dismissing for this session is still better than not dismissing */
    }
    setVisible(false);
  };

  return (
    <div
      role="region"
      aria-label="Functional storage notice"
      className="fixed inset-x-0 bottom-0 z-50 border-t border-dark-700 bg-dark-900/95 backdrop-blur"
    >
      <div className="mx-auto flex max-w-5xl flex-col gap-3 px-4 py-4 text-sm text-dark-300 sm:flex-row sm:items-center sm:justify-between">
        <p className="leading-relaxed">
          This site uses functional browser storage and cookies where necessary to provide its
          features. No tracking or analytics functions are used. Some features transmit data to
          Cardano Foundation infrastructure, the Cardano network or third-party services in order
          to function.{" "}
          <Link
            href="/legal#privacy"
            className="text-primary-400 underline hover:text-primary-300"
          >
            Learn more
          </Link>
        </p>
        <button
          type="button"
          onClick={acknowledge}
          className="shrink-0 self-start rounded border border-dark-600 bg-dark-800 px-4 py-2 text-sm text-dark-100 transition-colors hover:border-primary-500/40 hover:text-primary-400 sm:self-auto"
        >
          Got it
        </button>
      </div>
    </div>
  );
}
