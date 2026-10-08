import { describe, expect, it } from 'vitest';
import { safeNext } from './safeNext';

describe('safeNext', () => {
  it.each([
    ['/album/42', '/album/42'],
    ['/u/ana?tab=albuns', '/u/ana?tab=albuns'],
  ])('keeps the same-origin path %s', (raw, expected) => {
    expect(safeNext(raw)).toBe(expected);
  });

  it.each([null, '', 'album/42', '//evil.example', '/\\evil.example', 'https://evil.example', 'javascript:alert(1)'])(
    'falls back to / for %s',
    (raw) => {
      expect(safeNext(raw)).toBe('/');
    },
  );
});
