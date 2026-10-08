import { useState } from 'react';
import { Link, useLocation } from 'react-router';
import { ApiError, resendVerificationEmail } from '../auth/authApi';
import { Alert } from '../ui/Alert';
import { AuthLayout } from '../ui/AuthLayout';
import { MailIcon } from '../ui/icons';

type Resend = 'idle' | 'sending' | 'sent' | 'rate-limited' | 'unavailable';

export function VerifyEmailSentPage() {
  const location = useLocation();
  // Router state only: the address is not put in the URL (it would land in logs and history).
  const { email, username } = (location.state ?? {}) as { email?: string; username?: string };
  const [resend, setResend] = useState<Resend>('idle');

  async function handleResend() {
    if (!email) {
      return;
    }
    setResend('sending');
    try {
      await resendVerificationEmail(email);
      setResend('sent');
    } catch (error) {
      setResend(error instanceof ApiError && error.status === 429 ? 'rate-limited' : 'unavailable');
    }
  }

  return (
    <AuthLayout titleId="verify-title" centered>
      <div className="auth-mail" aria-hidden="true">
        <MailIcon />
      </div>
      <h1 id="verify-title">Verifique seu email</h1>
      <p className="auth-lead">
        {email ? (
          <>
            Enviamos um link de confirmação para <b>{email}</b>.
          </>
        ) : (
          'Enviamos um link de confirmação para o seu email.'
        )}{' '}
        Abra o email e clique no link para ativar sua conta.
      </p>
      {resend === 'sent' && (
        <p className="auth-ok" role="status">
          Email reenviado. Confira também a caixa de spam.
        </p>
      )}
      {resend === 'rate-limited' && <Alert title="Muitos reenvios.">Espere alguns minutos e tente de novo.</Alert>}
      {resend === 'unavailable' && <Alert title="Não foi possível reenviar agora.">Tente de novo em instantes.</Alert>}
      {email && (
        <button className="btn btn--secondary btn--full" type="button" disabled={resend === 'sending'} onClick={() => void handleResend()}>
          Reenviar email
        </button>
      )}
      <p className="auth-alt">
        Não recebeu? Confira o spam ou reenvie.
        <br />
        Email errado?{' '}
        <Link to="/cadastro" state={{ email, username }}>
          Voltar e corrigir
        </Link>
      </p>
    </AuthLayout>
  );
}
