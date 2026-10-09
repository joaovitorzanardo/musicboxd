import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router';
import { fakeApi, json, problem, tokenResponse } from '../test/fakeApi';
import { LocationProbe, renderApp } from '../test/renderApp';

const ANA = { id: '1', email: 'ana@exemplo.com', username: 'ana' };

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Senha'), password);
  await user.click(screen.getByRole('button', { name: 'Entrar' }));
}

describe('LoginPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('logs in and returns to ?next, keeping the session in memory without another refresh', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/login': () => tokenResponse('a1'),
      'GET /api/v1/accounts/me': () => json(200, ANA),
    });
    renderApp('/entrar?next=%2Falbum%2F42', <Route path="/album/:id" element={<LocationProbe />} />);

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByTestId('location')).toHaveTextContent('/album/42');
    expect(calls.filter((c) => c.url === '/api/v1/auth/refresh')).toHaveLength(1); // the page-load one only
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/auth/login')!.init.body as string)).toEqual({
      email: 'ana@exemplo.com',
      password: 'correct-horse',
    });
  });

  it('shows the credentials alert and marks both fields on a 401', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/auth/login': () => problem(401) });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'wrong-password');

    expect(await screen.findByRole('alert')).toHaveTextContent('Email ou senha incorretos. Confira os dados e tente de novo.');
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Senha')).toHaveAttribute('aria-invalid', 'true');
  });

  it('asks to wait on a 429 instead of blaming the credentials', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/auth/login': () => problem(429) });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Espere alguns minutos e tente de novo.');
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-invalid', 'true');
  });

  it('sends an unverified account to "Verifique seu email" with its address', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/login': () => problem(403, 'urn:musicboxd:problem:email-not-verified'),
    });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByRole('heading', { name: 'Verifique seu email' })).toBeInTheDocument();
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument();
  });

  it('asks for the missing fields without calling the API', async () => {
    const { calls } = fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    const user = userEvent.setup();
    renderApp('/entrar');

    await user.click(await screen.findByRole('button', { name: 'Entrar' }));

    expect(screen.getByText('Informe seu email.')).toBeInTheDocument();
    expect(screen.getByText('Informe sua senha.')).toBeInTheDocument();
    expect(calls.some((c) => c.url === '/api/v1/auth/login')).toBe(false);
  });

  it('shows a retry message when the API cannot be reached', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/login': () => Promise.reject(new TypeError('Failed to fetch')),
    });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível entrar agora.');
  });

  it('links to sign-up, keeping ?next', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp('/entrar?next=%2Falbum%2F42');

    expect(await screen.findByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/cadastro?next=%2Falbum%2F42');
  });
});
