import type { ReactNode } from 'react';
import { Link } from 'react-router';
import logo from '../assets/musicboxd-logo-dark.svg';

/** Auth card (EXPERIENCE.md card-auth): large logo above, no top bar. */
export function AuthLayout({ titleId, centered = false, children }: { titleId: string; centered?: boolean; children: ReactNode }) {
  return (
    <div className="auth-page">
      <div className="auth-brand">
        <Link className="auth-logo" to="/" aria-label="musicboxd - ir para o feed">
          <img src={logo} alt="" />
        </Link>
      </div>
      <main className="auth-main">
        <section className={centered ? 'auth-card auth-card--center' : 'auth-card'} aria-labelledby={titleId}>
          {children}
        </section>
      </main>
    </div>
  );
}
