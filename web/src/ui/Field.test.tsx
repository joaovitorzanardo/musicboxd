import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { PasswordField, TextField } from './Field';

function Password() {
  const [value, setValue] = useState('segredo123');
  return <PasswordField id="p" label="Senha" value={value} onChange={setValue} help="Mínimo de 8 caracteres." />;
}

describe('Field', () => {
  it('shows and hides the password with a labelled eye button', async () => {
    const user = userEvent.setup();
    render(<Password />);
    const input = screen.getByLabelText('Senha');
    expect(input).toHaveAttribute('type', 'password');

    await user.click(screen.getByRole('button', { name: 'Mostrar senha' }));
    expect(input).toHaveAttribute('type', 'text');

    await user.click(screen.getByRole('button', { name: 'Ocultar senha' }));
    expect(input).toHaveAttribute('type', 'password');
  });

  it('links the error and the help text to the input for screen readers', () => {
    render(<TextField id="e" label="Email" value="" onChange={() => {}} error="Informe um email válido." help="Ajuda" />);
    const input = screen.getByLabelText('Email');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Informe um email válido. Ajuda');
  });
});
