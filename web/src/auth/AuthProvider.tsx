import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { setAuthRequiredHandler } from './apiFetch';
import { fetchMe, login, logout } from './authApi';
import { safeNext } from './safeNext';
import { endSession, onSessionEnd, refreshSession, startSession } from './session';
import type { Account } from './types';

export type AuthState = { status: 'loading' } | { status: 'guest' } | { status: 'signed-in'; account: Account };

type AuthContextValue = {
  state: AuthState;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });
  const navigate = useNavigate();
  const location = useLocation();
  const here = useRef(location);

  useEffect(() => {
    here.current = location;
  }, [location]);

  // Page load: the cookie (if any) buys a fresh access token. StrictMode's second run joins the same refresh.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const outcome = await refreshSession();
      if (cancelled) {
        return;
      }
      if (outcome !== 'refreshed') {
        setState({ status: 'guest' });
        return;
      }
      try {
        const account = await fetchMe();
        if (!cancelled) {
          setState({ status: 'signed-in', account });
        }
      } catch {
        if (!cancelled) {
          endSession();
          setState({ status: 'guest' });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // The refresh cookie was refused mid-use (expired, revoked, reused): back to Guest.
  useEffect(() => onSessionEnd(() => setState({ status: 'guest' })), []);

  // The Guest boundary: a write that ends in 401 goes to sign-up and remembers where it came from.
  useEffect(
    () =>
      setAuthRequiredHandler(() => {
        const { pathname, search } = here.current;
        navigate(`/cadastro?next=${encodeURIComponent(pathname + search)}`);
      }),
    [navigate],
  );

  const signIn = useCallback(async (email: string, password: string) => {
    startSession(await login(email, password));
    try {
      setState({ status: 'signed-in', account: await fetchMe() });
    } catch (error) {
      endSession();
      throw error;
    }
  }, []);

  const signOut = useCallback(async () => {
    try {
      await logout();
    } catch {
      // The local session ends anyway; the cookie expires on its own.
    }
    endSession();
    setState({ status: 'guest' });
    navigate('/');
  }, [navigate]);

  const value = useMemo(() => ({ state, signIn, signOut }), [state, signIn, signOut]);
  return <AuthContext value={value}>{children}</AuthContext>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return value;
}

/** Login, sign-up and "check your email" are for Guests; a signed-in person goes on to ?next (or home). */
export function GuestOnly({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const [params] = useSearchParams();
  if (state.status === 'loading') {
    return null;
  }
  if (state.status === 'signed-in') {
    return <Navigate to={safeNext(params.get('next'))} replace />;
  }
  return children;
}
