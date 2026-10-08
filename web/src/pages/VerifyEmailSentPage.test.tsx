import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

const AT_VERIFY = { pathname: '/verifique-email', state: { email: 'ana@exemplo.com', username: 'ana_silva' } };

describe('VerifyEmailSentPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('names the address the link went to', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp(AT_VERIFY);

    expect(await screen.findByRole('heading', { name: 'Verifique seu email' })).toBeInTheDocument();
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument();
  });

  it('resends the email and confirms it', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/verification-email': () => new Response(null, { status: 202 }),
    });
    const user = userEvent.setup();
    renderApp(AT_VERIFY);

    await user.click(await screen.findByRole('button', { name: 'Reenviar email' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Email reenviado. Confira também a caixa de spam.');
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/auth/verification-email')!.init.body as string)).toEqual({
      email: 'ana@exemplo.com',
    });
  });

  it('explains the wait when resending too often (429)', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/verification-email': () => problem(429),
    });
    const user = userEvent.setup();
    renderApp(AT_VERIFY);

    await user.click(await screen.findByRole('button', { name: 'Reenviar email' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitos reenvios. Espere alguns minutos e tente de novo.');
  });

  it('without a known address (hard refresh) it hides the resend button', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp('/verifique-email');

    expect(await screen.findByText(/Enviamos um link de confirmação para o seu email\./)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reenviar email' })).toBeNull();
  });

  it('"Voltar e corrigir" returns to sign-up with the fields filled in', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    const user = userEvent.setup();
    renderApp(AT_VERIFY);

    await user.click(await screen.findByRole('link', { name: 'Voltar e corrigir' }));

    expect(await screen.findByLabelText('Email')).toHaveValue('ana@exemplo.com');
    expect(screen.getByLabelText('Nome de usuário')).toHaveValue('ana_silva');
  });
});
