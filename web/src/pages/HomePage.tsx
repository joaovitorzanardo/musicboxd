import { Link } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { HealthStatus } from '../HealthStatus';

export function HomePage() {
  const { state, signOut } = useAuth();
  return (
    <main className="home">
      <h1>Musicboxd</h1>
      {state.status === 'guest' && (
        <nav aria-label="Conta" className="home-account">
          <Link to="/entrar">Entrar</Link>
          <Link to="/cadastro">Criar conta</Link>
        </nav>
      )}
      {state.status === 'signed-in' && (
        <p className="home-account">
          Conectado como @{state.account.username}{' '}
          <button type="button" className="btn btn--secondary" onClick={() => void signOut()}>
            Sair
          </button>
        </p>
      )}
      <HealthStatus />
    </main>
  );
}
