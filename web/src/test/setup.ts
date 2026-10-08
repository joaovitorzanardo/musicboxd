import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { resetSessionForTests } from '../auth/session';
import { setAuthRequiredHandler } from '../auth/apiFetch';

afterEach(() => {
  cleanup();
  resetSessionForTests();
  setAuthRequiredHandler(() => {});
});
