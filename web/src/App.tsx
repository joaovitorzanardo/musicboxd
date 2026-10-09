import type { ReactNode } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';
import { AuthProvider, GuestOnly } from './auth/AuthProvider';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { VerifyEmailPage } from './pages/VerifyEmailPage';
import { VerifyEmailSentPage } from './pages/VerifyEmailSentPage';

/** `children`: extra <Route>s (tests use this to add a page that does a write). */
export function AppRoutes({ children }: { children?: ReactNode }) {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/entrar" element={<GuestOnly><LoginPage /></GuestOnly>} />
      <Route path="/cadastro" element={<GuestOnly><RegisterPage /></GuestOnly>} />
      <Route path="/verifique-email" element={<GuestOnly><VerifyEmailSentPage /></GuestOnly>} />
      {/* Not GuestOnly: the emailed link may open in a browser that already has a session (MBD-63). */}
      <Route path="/verificar-email" element={<VerifyEmailPage />} />
      {children}
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}
