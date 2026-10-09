import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { ApiError, PROBLEM_TYPES } from '../auth/authApi';
import { safeNext } from '../auth/safeNext';
import { Alert } from '../ui/Alert';
import { AuthLayout } from '../ui/AuthLayout';
import { PasswordField, TextField } from '../ui/Field';

type Failure = 'credentials' | 'rate-limited' | 'unavailable' | null;

export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [missing, setMissing] = useState({ email: false, password: false });
  const [failure, setFailure] = useState<Failure>(null);
  const [submitting, setSubmitting] = useState(false);
  const query = params.toString() ? `?${params.toString()}` : '';

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nowMissing = { email: email.trim() === '', password: password === '' };
    setMissing(nowMissing);
    setFailure(null);
    if (nowMissing.email || nowMissing.password) {
      return;
    }
    setSubmitting(true);
    try {
      await signIn(email, password);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.type === PROBLEM_TYPES.emailNotVerified) {
        navigate('/verifique-email', { state: { email: email.trim() } });
        return;
      }
      if (error instanceof ApiError && error.status === 401) {
        setFailure('credentials');
      } else if (error instanceof ApiError && error.status === 429) {
        setFailure('rate-limited');
      } else {
        setFailure('unavailable');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout titleId="login-title">
      <h1 id="login-title">Entrar</h1>
      <p className="auth-lead">Bem-vindo de volta ao musicboxd.</p>
      {failure === 'credentials' && <Alert title="Email ou senha incorretos.">Confira os dados e tente de novo.</Alert>}
      {failure === 'rate-limited' && <Alert title="Muitas tentativas.">Espere alguns minutos e tente de novo.</Alert>}
      {failure === 'unavailable' && <Alert title="Não foi possível entrar agora.">Tente de novo em instantes.</Alert>}
      <form className="auth-form" noValidate onSubmit={(event) => void handleSubmit(event)}>
        <TextField
          id="login-email"
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="voce@exemplo.com"
          value={email}
          onChange={setEmail}
          invalid={failure === 'credentials'}
          error={missing.email ? 'Informe seu email.' : undefined}
        />
        <PasswordField
          id="login-password"
          label="Senha"
          autoComplete="current-password"
          value={password}
          onChange={setPassword}
          invalid={failure === 'credentials'}
          error={missing.password ? 'Informe sua senha.' : undefined}
        />
        <button className="btn btn--full" type="submit" disabled={submitting}>
          Entrar
        </button>
      </form>
      <p className="auth-alt">
        Novo por aqui? <Link to={`/cadastro${query}`}>Criar conta</Link>
      </p>
    </AuthLayout>
  );
}
