import { useLocation } from 'react-router';

export function LoginPage() {
  const { pathname, search } = useLocation();
  return <p data-testid="page">{decodeURIComponent(pathname + search)}</p>;
}
