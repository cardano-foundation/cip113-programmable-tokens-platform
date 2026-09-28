/**
 * The registry's domain model — what a node IS, independent of how it is fetched.
 *
 * Deliberately free of imports. The walk is pure logic over these shapes and must stay testable
 * without dragging in the HTTP client, Next's path aliases, or anything that needs a browser.
 * `lib/api/registry.ts` re-exports these so callers have one import site.
 */
/**
 * The end-of-list marker: thirty bytes of 0xff.
 *
 * Thirty, not twenty-eight — deliberately wider than any policy id, so it always sorts last and no
 * real key can collide with it.
 */
export const MAX_NEXT = 'ff'.repeat(30);
/** The list head's key. Empty, so it sorts below every key. */
export const SENTINEL_KEY = '';
