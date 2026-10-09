import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, json, problem, tokenResponse } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

const SIGNED_OUT = { 'POST /api/v1/auth/refresh': () => problem(401) };
const verified = () => json(200, { status: 'verified' });

describe('VerifyEmailPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('confirms a valid link with "Tudo certo!" and one Entrar button', async () => {
    const { calls } = fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': verified });
    renderApp('/verificar-email?token=abc_DEF-123');

    const heading = await screen.findByRole('heading', { name: 'Tudo certo!' });
    expect(screen.getByText('Seu email foi confirmado. Agora é só entrar e começar a avaliar seus álbuns.')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Entrar' })).toHaveLength(1);
    expect(screen.queryByRole('button')).toBeNull();
    expect(heading).toHaveFocus();
    const verifyCalls = calls.filter((c) => c.url.startsWith('/api/v1/auth/verify'));
    expect(verifyCalls.map((c) => c.url)).toEqual(['/api/v1/auth/verify?token=abc_DEF-123']);
  });

  it('shows "Tudo certo!" again when the link was already used (the API answers 200)', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': [verified, verified] });
    const first = renderApp('/verificar-email?token=used');
    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
    first.unmount();

    renderApp('/verificar-email?token=used');

    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
  });

  it('shows "Link inválido ou expirado" when the API rejects the token (400)', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': () => problem(400) });
    renderApp('/verificar-email?token=tampered');

    const heading = await screen.findByRole('heading', { name: 'Link inválido ou expirado' });
    expect(
      screen.getByText(
        'Este link de confirmação não vale mais. Entre com seu email e senha: se a conta ainda não estiver ativa, mostramos como receber um novo link.',
      ),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Entrar' })).toHaveLength(1);
    expect(heading).toHaveFocus();
  });

  it('a link without a token is invalid without asking the API', async () => {
    const { calls } = fakeApi(SIGNED_OUT);
    renderApp('/verificar-email?token=');

    expect(await screen.findByRole('heading', { name: 'Link inválido ou expirado' })).toBeInTheDocument();
    expect(calls.some((c) => c.url.startsWith('/api/v1/auth/verify'))).toBe(false);
  });

  it('keeps "Confirmando seu email…" a heading while the request is in flight', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': () => new Promise<Response>(() => {}) });
    renderApp('/verificar-email?token=abc');

    expect(await screen.findByRole('heading', { name: 'Confirmando seu email…' })).toBeInTheDocument();
  });

  it.each([429, 502, 503])('does not call a working link invalid when the API answers %i', async (status) => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': () => problem(status) });
    renderApp('/verificar-email?token=abc');

    const heading = await screen.findByRole('heading', { name: 'Não foi possível confirmar agora' });
    expect(screen.getByText('Tente de novo em instantes.')).toBeInTheDocument();
    expect(heading).toHaveFocus();
    expect(screen.queryByRole('heading', { name: 'Link inválido ou expirado' })).toBeNull();
  });

  it('"Tentar de novo" retries after a failure and then confirms', async () => {
    fakeApi({
      ...SIGNED_OUT,
      'GET /api/v1/auth/verify': [() => Promise.reject(new TypeError('Failed to fetch')), verified],
    });
    const user = userEvent.setup();
    renderApp('/verificar-email?token=abc');

    await user.click(await screen.findByRole('button', { name: 'Tentar de novo' }));

    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
  });

  it('shows the outcome to a visitor who is already signed in', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse(),
      'GET /api/v1/accounts/me': () => json(200, { id: '1', email: 'ana@exemplo.com', username: 'ana' }),
      'GET /api/v1/auth/verify': verified,
    });
    renderApp('/verificar-email?token=abc');

    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
  });

  it('Entrar goes to the login screen', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': () => problem(400) });
    const user = userEvent.setup();
    renderApp('/verificar-email?token=x');

    await user.click(await screen.findByRole('link', { name: 'Entrar' }));

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });
});
