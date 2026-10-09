import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { ApiError, PROBLEM_TYPES, register } from '../auth/authApi';
import { Alert } from '../ui/Alert';
import { AuthLayout } from '../ui/AuthLayout';
import { PasswordField, TextField } from '../ui/Field';

type Input = { username: string; email: string; password: string };
type FieldErrors = Partial<Record<keyof Input, ReactNode>>;
type Failure = 'rejected' | 'rate-limited' | 'unavailable' | null;

// Same rules as AuthController.RegisterRequest; the server stays the authority (AD-7).
const USERNAME = /^[A-Za-z0-9_]{3,20}$/;
const EMAIL = /^[^\s@]+@[^\s@]+$/;
const MIN_PASSWORD = 8;

export function validateRegistration(input: Input): FieldErrors {
  const errors: FieldErrors = {};
  if (!USERNAME.test(input.username)) {
    errors.username = 'Use de 3 a 20 letras, números ou sublinhado.';
  }
  if (!EMAIL.test(input.email)) {
    errors.email = 'Informe um email válido.';
  }
  if (input.password.length < MIN_PASSWORD) {
    errors.password = 'A senha precisa ter pelo menos 8 caracteres.';
  }
  return errors;
}

export function RegisterPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const prefill = (location.state ?? {}) as { email?: string; username?: string };
  const [username, setUsername] = useState(prefill.username ?? '');
  const [email, setEmail] = useState(prefill.email ?? '');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<FieldErrors>({});
  const [failure, setFailure] = useState<Failure>(null);
  const [submitting, setSubmitting] = useState(false);
  const query = params.toString() ? `?${params.toString()}` : '';

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const input = { username: username.trim().replace(/^@/, ''), email: email.trim(), password };
    const found = validateRegistration(input);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSubmitting(true);
    try {
      await register(input);
      navigate('/verifique-email', { state: { email: input.email, username: input.username } });
    } catch (error) {
      if (error instanceof ApiError && error.type === PROBLEM_TYPES.emailTaken) {
        setErrors({ email: <>Este email já está em uso. <Link to={`/entrar${query}`}>Entrar</Link></> });
      } else if (error instanceof ApiError && error.type === PROBLEM_TYPES.usernameTaken) {
        setErrors({ username: 'Este nome de usuário já está em uso.' });
      } else {
        if (error instanceof ApiError && error.status === 400) {
          setFailure('rejected');
        } else if (error instanceof ApiError && error.status === 429) {
          setFailure('rate-limited');
        } else {
          setFailure('unavailable');
        }
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout titleId="register-title">
      <h1 id="register-title">Criar conta</h1>
      <p className="auth-lead">Só precisa de um nome de usuário, email e senha.</p>
      {Object.keys(errors).length > 0 && <Alert title="Não foi possível criar a conta.">Corrija os campos destacados.</Alert>}
      {failure === 'rejected' && <Alert title="Não foi possível criar a conta.">Confira os dados e tente de novo.</Alert>}
      {failure === 'rate-limited' && (
        <Alert title="Muitas tentativas de cadastro.">Espere alguns minutos e tente de novo.</Alert>
      )}
      {failure === 'unavailable' && <Alert title="Não foi possível criar a conta agora.">Tente de novo em instantes.</Alert>}
      <form className="auth-form" noValidate onSubmit={(event) => void handleSubmit(event)}>
        <TextField
          id="register-username"
          label="Nome de usuário"
          prefix="@"
          autoComplete="username"
          placeholder="seunome"
          value={username}
          onChange={setUsername}
          error={errors.username}
          help="Aparece no seu perfil. De 3 a 20 letras, números ou sublinhado."
        />
        <TextField
          id="register-email"
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="voce@exemplo.com"
          value={email}
          onChange={setEmail}
          error={errors.email}
        />
        <PasswordField
          id="register-password"
          label="Senha"
          autoComplete="new-password"
          value={password}
          onChange={setPassword}
          error={errors.password}
          help="Mínimo de 8 caracteres."
        />
        <button className="btn btn--full" type="submit" disabled={submitting}>
          Criar conta
        </button>
      </form>
      <p className="auth-alt">
        Já tem conta? <Link to={`/entrar${query}`}>Entrar</Link>
      </p>
    </AuthLayout>
  );
}
