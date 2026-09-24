import Link from "next/link";

/** Shown for any route that does not exist — including /ops/* while the
 *  operator tools are switched off, which is deliberately indistinguishable
 *  from a page that was never built. See middleware.ts. */
export default function NotFound() {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 px-4 text-center">
      <p className="text-5xl font-bold text-dark-600">404</p>
      <h1 className="text-xl font-semibold text-white">Page not found</h1>
      <p className="max-w-md text-sm text-dark-400">
        The page you are looking for does not exist.
      </p>
      <Link
        href="/"
        className="mt-2 rounded border border-dark-600 bg-dark-800 px-4 py-2 text-sm text-dark-100 transition-colors hover:border-primary-500/40 hover:text-primary-400"
      >
        Back home
      </Link>
    </div>
  );
}
