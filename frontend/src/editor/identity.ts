// Shared display-identity helpers: several entities (projects, assets) fall back to a short,
// human-scannable id when they have no user-given name/filename yet.

/** First 8 characters of a UUID (or the whole string, if shorter) — e.g. "a1b2c3d4". */
export function shortId(id: string): string {
  return id.slice(0, 8)
}

/** `name` when present, otherwise "<prefix> <shortId(id)>" — e.g. "Project a1b2c3d4". */
export function labelWithFallback(name: string | null, id: string, prefix: string): string {
  return name ?? `${prefix} ${shortId(id)}`
}
