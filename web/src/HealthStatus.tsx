import { useEffect, useState } from 'react';

type HealthState =
  | { kind: 'loading' }
  | { kind: 'ok'; status: string }
  | { kind: 'error'; message: string };

export function HealthStatus() {
  const [health, setHealth] = useState<HealthState>({ kind: 'loading' });

  useEffect(() => {
    const controller = new AbortController();

    fetch('/api/v1/health', { signal: controller.signal })
      .then((response) => {
        if (controller.signal.aborted) {
          return undefined;
        }
        if (!response.ok) {
          throw new Error(`Health check failed with status ${response.status}`);
        }
        return response.json() as Promise<unknown>;
      })
      .then((body) => {
        if (controller.signal.aborted) {
          return;
        }
        if (
          typeof body !== 'object' ||
          body === null ||
          typeof (body as { status?: unknown }).status !== 'string'
        ) {
          throw new Error('Malformed health response');
        }
        setHealth({ kind: 'ok', status: (body as { status: string }).status });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
          return;
        }
        const message = error instanceof Error ? error.message : 'Unknown error';
        setHealth({ kind: 'error', message });
      });

    return () => controller.abort();
  }, []);

  return (
    <>
      {health.kind === 'loading' && <p role="status">Checking API health…</p>}
      {health.kind === 'ok' && <p role="status">API status: {health.status}</p>}
      {health.kind === 'error' && <p role="alert">Could not reach the API: {health.message}</p>}
    </>
  );
}
