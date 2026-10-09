import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ApiError, verifyEmail } from '../auth/authApi';
import { AuthLayout } from '../ui/AuthLayout';
import { CheckIcon, WarningIcon } from '../ui/icons';

type Outcome = 'checking' | 'verified' | 'invalid' | 'unavailable';

/**
 * Where the verification email's link lands (MBD-63). Open to everyone, signed in or not.
 * A reused link is still "verified" (the API answers 200). Only a 400 means the link is bad;
 * a network or server failure says nothing about the link, so it offers a retry instead.
 */
export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token')?.trim() ?? '';
  const [result, setResult] = useState<Outcome>('checking');
  const [attempt, setAttempt] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const outcome: Outcome = token ? result : 'invalid';

  useEffect(() => {
    if (!token) {
      return;
    }
    // StrictMode runs this twice in dev: both calls answer 200, and the first one's result is dropped.
    let current = true;
    setResult('checking');
    verifyEmail(token).then(
      () => current && setResult('verified'),
      (error: unknown) => current && setResult(error instanceof ApiError && error.status === 400 ? 'invalid' : 'unavailable'),
    );
    return () => {
      current = false;
    };
  }, [token, attempt]);

  // Layout effect: the heading has focus in the same commit that shows it, so it is announced at once.
  useLayoutEffect(() => {
    if (outcome !== 'checking') {
      headingRef.current?.focus();
    }
  }, [outcome]);

  if (outcome === 'checking') {
    return (
      <AuthLayout titleId="confirm-title" centered>
        <h1 id="confirm-title">Confirmando seu email…</h1>
      </AuthLayout>
    );
  }

  if (outcome === 'unavailable') {
    return (
      <AuthLayout titleId="confirm-title" centered>
        <h1 id="confirm-title" tabIndex={-1} ref={headingRef}>
          Não foi possível confirmar agora
        </h1>
        <p className="auth-lead">Tente de novo em instantes.</p>
        <button className="btn btn--full" type="button" onClick={() => setAttempt((n) => n + 1)}>
          Tentar de novo
        </button>
      </AuthLayout>
    );
  }

  const verified = outcome === 'verified';
  return (
    <AuthLayout titleId="confirm-title" centered>
      <div className={verified ? 'auth-mail' : 'auth-mail auth-mail--bad'} aria-hidden="true">
        {verified ? <CheckIcon /> : <WarningIcon />}
      </div>
      <h1 id="confirm-title" tabIndex={-1} ref={headingRef}>
        {verified ? 'Tudo certo!' : 'Link inválido ou expirado'}
      </h1>
      <p className="auth-lead">
        {verified
          ? 'Seu email foi confirmado. Agora é só entrar e começar a avaliar seus álbuns.'
          : 'Este link de confirmação não vale mais. Entre com seu email e senha: se a conta ainda não estiver ativa, mostramos como receber um novo link.'}
      </p>
      <Link className="btn btn--full" to="/entrar">
        Entrar
      </Link>
    </AuthLayout>
  );
}
