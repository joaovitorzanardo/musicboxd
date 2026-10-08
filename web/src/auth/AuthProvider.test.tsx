import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router';
import { apiFetch } from './apiFetch';
import { fakeApi, json, problem, tokenResponse } from '../test/fakeApi';
import { LocationProbe, renderApp } from '../test/renderApp';

const ANA = { id: '1', email: 'ana@exemplo.com', username: 'ana' };
const health = () => json(200, { status: 'ok' });

function RateButton() {
  return (
    <button type="button" onClick={() => void apiFetch('/api/v1/ratings', { method: 'POST', body: '{}' })}>
      Avaliar
    </button>
  );
}

describe('AuthProvider', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('restores the session from the refresh cookie on a hard refresh', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'GET /api/v1/accounts/me': () => json(200, ANA),
      'GET /api/v1/health': health,
    });

    renderApp('/');

    expect(await screen.findByText(/Conectado como @ana/)).toBeInTheDocument();
  });

  it('is a Guest after a hard refresh without a working refresh cookie', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'GET /api/v1/health': health });

    renderApp('/');

    expect(await screen.findByRole('link', { name: 'Entrar' })).toHaveAttribute('href', '/entrar');
    expect(screen.getByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/cadastro');
  });

  it('sends a Guest whose write gets a 401 to sign-up, remembering where they were', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/ratings': () => problem(401) });
    const user = userEvent.setup();
    renderApp('/album/42', <Route path="/album/:id" element={<RateButton />} />);

    await user.click(await screen.findByRole('button', { name: 'Avaliar' }));

    expect(await screen.findByRole('heading', { name: 'Criar conta' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Entrar' })).toHaveAttribute('href', '/entrar?next=%2Falbum%2F42');
  });

  it('signing out revokes the cookie and leaves a Guest', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'DELETE /api/v1/auth/refresh': () => new Response(null, { status: 204 }),
      'GET /api/v1/accounts/me': () => json(200, ANA),
      'GET /api/v1/health': health,
    });
    const user = userEvent.setup();
    renderApp('/');

    await user.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('link', { name: 'Entrar' })).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'DELETE' && c.url === '/api/v1/auth/refresh')).toBe(true);
  });

  it('ends the local session even when the logout call fails', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'DELETE /api/v1/auth/refresh': () => Promise.reject(new TypeError('Failed to fetch')),
      'GET /api/v1/accounts/me': () => json(200, ANA),
      'GET /api/v1/health': health,
    });
    const user = userEvent.setup();
    renderApp('/');

    await user.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('link', { name: 'Entrar' })).toBeInTheDocument();
  });

  it('sends a signed-in person away from the auth screens', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'GET /api/v1/accounts/me': () => json(200, ANA),
    });

    renderApp('/entrar?next=%2Falbum%2F42', <Route path="/album/:id" element={<LocationProbe />} />);

    expect(await screen.findByTestId('location')).toHaveTextContent('/album/42');
  });
});
