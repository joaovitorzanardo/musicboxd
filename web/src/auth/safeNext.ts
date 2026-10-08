/** Where to go after login: only a path on this origin. `//host` and `/\host` are other origins to a browser. */
export function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) {
    return '/';
  }
  return raw;
}
