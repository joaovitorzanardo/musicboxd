const base = { width: 20, height: 20, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const;

export function EyeIcon() {
  return (
    <svg {...base}>
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

export function EyeOffIcon() {
  return (
    <svg {...base}>
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}

export function MailIcon() {
  return (
    <svg {...base} width={26} height={26}>
      <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
      <polyline points="22,6 12,13 2,6" />
    </svg>
  );
}

export function CheckIcon() {
  return (
    <svg {...base} width={30} height={30}>
      <path d="M5 12.5 10 17.5 19 7" />
    </svg>
  );
}

export function WarningIcon() {
  return (
    <svg {...base} width={30} height={30}>
      <path d="M12 9v5" />
      <path d="M12 4 2.8 19.5h18.4z" />
      <circle cx="12" cy="17" r=".6" fill="currentColor" />
    </svg>
  );
}
