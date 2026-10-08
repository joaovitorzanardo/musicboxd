import { useState, type ReactNode } from 'react';
import { EyeIcon, EyeOffIcon } from './icons';

export type TextFieldProps = {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'email' | 'password';
  placeholder?: string;
  autoComplete?: string;
  /** Shown inside the input on the left, e.g. "@" for the username. */
  prefix?: string;
  help?: string;
  error?: ReactNode;
  /** Red border without a message of its own (login's shared "wrong credentials" alert). */
  invalid?: boolean;
  trailing?: ReactNode;
};

export function TextField({ id, label, value, onChange, type = 'text', placeholder, autoComplete, prefix, help, error, invalid, trailing }: TextFieldProps) {
  const errorId = error ? `${id}-error` : undefined;
  const helpId = help ? `${id}-help` : undefined;
  const describedBy = [errorId, helpId].filter(Boolean).join(' ') || undefined;
  const bad = Boolean(error) || Boolean(invalid);
  const input = (
    <input
      id={id}
      type={type}
      value={value}
      placeholder={placeholder}
      autoComplete={autoComplete}
      className={bad ? 'is-bad' : undefined}
      aria-invalid={bad || undefined}
      aria-describedby={describedBy}
      onChange={(event) => onChange(event.target.value)}
    />
  );
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {prefix || trailing ? (
        <div className={`field-control${prefix ? ' field-control--prefix' : ''}${trailing ? ' field-control--trailing' : ''}`}>
          {prefix && <span aria-hidden="true">{prefix}</span>}
          {input}
          {trailing}
        </div>
      ) : (
        input
      )}
      {error && <p className="field-error" id={errorId}>{error}</p>}
      {help && <p className="field-help" id={helpId}>{help}</p>}
    </div>
  );
}

export function PasswordField(props: Omit<TextFieldProps, 'type' | 'prefix' | 'trailing'>) {
  const [visible, setVisible] = useState(false);
  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      trailing={
        <button
          type="button"
          className="field-toggle"
          aria-label={visible ? 'Ocultar senha' : 'Mostrar senha'}
          onClick={() => setVisible((shown) => !shown)}
        >
          {visible ? <EyeOffIcon /> : <EyeIcon />}
        </button>
      }
    />
  );
}
