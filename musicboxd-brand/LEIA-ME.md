# musicboxd — arquivos da marca

## Onde usar cada arquivo
| Arquivo | Uso |
|---|---|
| `musicboxd-logo-dark.svg` | Logo para fundo escuro (texto claro, disco coral). Fundo transparente. |
| `musicboxd-logo-light.svg` | Logo para fundo claro (texto escuro, disco preto). Fundo transparente. |
| `musicboxd-disc-dark.svg` / `-light.svg` | Só o disco: avatar, loading, ícones. |
| `favicon.svg` | Favicon que troca sozinho: disco preto em navegador claro, coral em navegador escuro. |
| `favicon.ico` | Favicon de reserva para navegadores antigos. |
| `apple-touch-icon.png`, `icon-192.png`, `icon-512.png` | Ícones de app (iPhone, Android, PWA). |
| `png/musicboxd-og-dark.png` | Imagem de compartilhamento (link no WhatsApp, Twitter etc.). |
| `png/*` | Versões PNG para usos fora do site (redes sociais, slides). |

O texto do logo já está convertido em vetor, então não depende da fonte Outfit estar instalada.

## Como colocar no site
Copie os arquivos da raiz para a pasta pública do site (`public/` no Vite/Next/React) e adicione no `<head>`:

```html
<link rel="icon" href="/favicon.ico" sizes="48x48">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<meta name="theme-color" content="#111317">
<meta property="og:image" content="/png/musicboxd-og-dark.png">
```

Logo que troca junto com o tema claro/escuro:

```html
<picture>
  <source srcset="/musicboxd-logo-dark.svg" media="(prefers-color-scheme: dark)">
  <img src="/musicboxd-logo-light.svg" alt="musicboxd" height="32">
</picture>
```

## Paleta
| Nome | Hex | Papel |
|---|---|---|
| Noite | `#111317` | Fundo escuro, texto no tema claro |
| Papel | `#F3EFE8` | Fundo claro, texto no tema escuro |
| Coral | `#FF5A3C` | Cor de destaque (única cor da marca) |
| Branco | `#FFFFFF` | Selo do disco coral |

Fonte: **Outfit Bold** para títulos (Google Fonts) e **Inter** para o texto do site.
