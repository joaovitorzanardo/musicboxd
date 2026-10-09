import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, json, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';
import { validateRegistration } from './RegisterPage';

async function fill(username: string, email: string, password: string) {
  const user = userEvent.setup();
  if (username) await user.type(await screen.findByLabelText('Nome de usuário'), username);
  if (email) await user.type(screen.getByLabelText('Email'), email);
  if (password) await user.type(screen.getByLabelText('Senha'), password);
  await user.click(screen.getByRole('button', { name: 'Criar conta' }));
}

describe('validateRegistration', () => {
  it('mirrors the API rules', () => {
    expect(validateRegistration({ username: 'ana_silva', email: 'ana@exemplo.com', password: '12345678' })).toEqual({});
    expect(validateRegistration({ username: 'an', email: 'ana', password: '1234567' })).toEqual({
      username: 'Use de 3 a 20 letras, números ou sublinhado.',
      email: 'Informe um email válido.',
      password: 'A senha precisa ter pelo menos 8 caracteres.',
    });
    expect(validateRegistration({ username: 'ana.silva', email: 'a@b', password: '12345678' }).username).toBeDefined();
    expect(validateRegistration({ username: 'a'.repeat(21), email: 'a@b', password: '12345678' }).username).toBeDefined();
  });
});

describe('RegisterPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('creates the account and moves to "Verifique seu email"', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/register': () => json(201, { id: '1', email: 'ana@exemplo.com', username: 'ana_silva' }),
    });
    renderApp('/cadastro');

    await fill('@ana_silva', ' ana@exemplo.com ', 'correct-horse');

    expect(await screen.findByRole('heading', { name: 'Verifique seu email' })).toBeInTheDocument();
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument();
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/auth/register')!.init.body as string)).toEqual({
      username: 'ana_silva', // a typed leading "@" is dropped
      email: 'ana@exemplo.com',
      password: 'correct-horse',
    });
  });

  it('shows field errors and the top alert without calling the API', async () => {
    const { calls } = fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp('/cadastro');

    await fill('ana', 'ana@exemplo.com', 'abc123');

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta. Corrija os campos destacados.');
    expect(screen.getByLabelText('Senha')).toHaveAccessibleDescription(
      'A senha precisa ter pelo menos 8 caracteres. Mínimo de 8 caracteres.',
    );
    expect(calls.some((c) => c.url === '/api/v1/auth/register')).toBe(false);
  });

  it('puts "email already in use" under the email field, with a link to log in', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/register': () => problem(409, 'urn:musicboxd:problem:email-taken'),
    });
    renderApp('/cadastro');

    await fill('ana_silva', 'ana@exemplo.com', 'correct-horse');

    expect(await screen.findByText(/Este email já está em uso\./)).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    // One "Entrar" in the field error, one in the footer.
    expect(screen.getAllByRole('link', { name: 'Entrar' })).toHaveLength(2);
  });

  it('puts "username taken" under the username field', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/register': () => problem(409, 'urn:musicboxd:problem:username-taken'),
    });
    renderApp('/cadastro');

    await fill('ana_silva', 'ana@exemplo.com', 'correct-horse');

    expect(await screen.findByText('Este nome de usuário já está em uso.')).toBeInTheDocument();
    expect(screen.getByLabelText('Nome de usuário')).toHaveAttribute('aria-invalid', 'true');
  });

  it('asks to wait on a 429', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/auth/register': () => problem(429) });
    renderApp('/cadastro');

    await fill('ana_silva', 'ana@exemplo.com', 'correct-horse');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Muitas tentativas de cadastro. Espere alguns minutos e tente de novo.',
    );
  });

  it('prefills username and email when coming back to fix them', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp({ pathname: '/cadastro', state: { email: 'ana@exemplo.com', username: 'ana_silva' } });

    expect(await screen.findByLabelText('Nome de usuário')).toHaveValue('ana_silva');
    expect(screen.getByLabelText('Email')).toHaveValue('ana@exemplo.com');
    expect(screen.getByLabelText('Senha')).toHaveValue('');
  });
});
