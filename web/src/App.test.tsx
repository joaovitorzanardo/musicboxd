import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders the health status once the API call resolves (happy path)', async () => {
    let resolveFetch!: (response: Response) => void;
    vi.mocked(fetch).mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve;
      }),
    );

    render(<App />);

    expect(screen.getByRole('status')).toHaveTextContent('Checking API health…');

    resolveFetch(new Response(JSON.stringify({ status: 'ok' }), { status: 200 }));

    expect(await screen.findByText('API status: ok')).toBeInTheDocument();
  });

  it('shows a visible error state, not an unhandled rejection, when the API is unreachable', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'));

    render(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the API');
  });

  it('shows an error state, not "undefined", when the response body has no string status', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({}), { status: 200 }));

    render(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Malformed health response');
  });

  it('shows an error state when the API responds with a non-2xx status', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('', { status: 500 }));

    render(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Health check failed with status 500',
    );
  });
});
