import type { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { AuthProvider } from '../auth/AuthProvider';
import { AppRoutes } from '../App';

type Entry = string | { pathname: string; search?: string; state?: unknown };

/** The real routes and provider at a given URL. `extraRoutes` adds test-only <Route>s. */
export function renderApp(entry: Entry, extraRoutes?: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider>
        <AppRoutes>{extraRoutes}</AppRoutes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

export function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}
