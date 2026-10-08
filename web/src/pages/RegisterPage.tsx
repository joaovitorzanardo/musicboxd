import { useLocation } from 'react-router';

export function RegisterPage() {
  const { pathname, search } = useLocation();
  return <p data-testid="page">{decodeURIComponent(pathname + search)}</p>;
}
