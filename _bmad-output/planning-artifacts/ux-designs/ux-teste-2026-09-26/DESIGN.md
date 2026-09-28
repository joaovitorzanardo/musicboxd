---
name: Musicboxd
description: Diário social de música. Noite e Papel como base, Coral como única cor de destaque; títulos em Outfit Bold, texto em Inter; tema escuro por padrão com tema claro.
status: final
updated: 2026-09-26
colors:
  coral: '#FF5A3C'
  on-coral: '#111317'
  bg: '#111317'
  fg: '#F3EFE8'
  muted: 'rgba(243,239,232,.62)'
  line: 'rgba(243,239,232,.14)'
  card: '#1a1d22'
  star-empty: 'rgba(243,239,232,.24)'
  err: '#FF8A73'
  err-bg: 'rgba(255,90,60,.12)'
  coral-tint: 'rgba(255,90,60,.14)'
  coral-tint-hover: 'rgba(255,90,60,.08)'
  coral-tint-active: 'rgba(255,90,60,.16)'
  focus-ring: 'rgba(255,90,60,.25)'
  bg-light: '#F3EFE8'
  fg-light: '#111317'
  muted-light: 'rgba(17,19,23,.66)'
  line-light: 'rgba(17,19,23,.16)'
  card-light: '#fbf9f5'
  star-empty-light: 'rgba(17,19,23,.22)'
  err-light: '#B3260E'
  err-bg-light: 'rgba(255,90,60,.10)'
  coral-text-light: '#C23516'
  brand-white: '#FFFFFF'
typography:
  display:
    fontFamily: Outfit
    fontSize: 44px
    fontWeight: 700
    lineHeight: 1.1
  h1-page:
    fontFamily: Outfit
    fontSize: 34px
    fontWeight: 700
    lineHeight: 1.1
  h1-admin:
    fontFamily: Outfit
    fontSize: 30px
    fontWeight: 700
    lineHeight: 1.15
  h1-auth:
    fontFamily: Outfit
    fontSize: 24px
    fontWeight: 700
    lineHeight: 1.2
  h2:
    fontFamily: Outfit
    fontSize: 22px
    fontWeight: 700
  h2-admin:
    fontFamily: Outfit
    fontSize: 20px
    fontWeight: 700
  item-title:
    fontFamily: Outfit
    fontSize: 17px
    fontWeight: 700
    lineHeight: 1.25
  label-caps:
    fontFamily: Outfit
    fontSize: 12px
    fontWeight: 700
    letterSpacing: 0.08em
  tab:
    fontFamily: Outfit
    fontSize: 16px
    fontWeight: 700
  button:
    fontFamily: Outfit
    fontSize: 15px
    fontWeight: 700
  body:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.5
  body-strong:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: 600
  nav:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: 500
  meta:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: 400
  caption:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: 400
  th-admin:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 500
    letterSpacing: 0.06em
  stars-md:
    fontFamily: Inter
    fontSize: 20px
    letterSpacing: 2px
  stars-lg:
    fontFamily: Inter
    fontSize: 30px
    letterSpacing: 2px
  stars-sm:
    fontFamily: Inter
    fontSize: 18px
    letterSpacing: 2px
rounded:
  xs: 4px
  sm: 6px
  md: 8px
  lg: 10px
  xl: 12px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 24px
  '6': 32px
  '7': 40px
  '8': 64px
  gutter-desktop: 32px
  gutter-mobile: 16px
  content-max: 960px
  content-narrow: 720px
  frame-desktop: 1280px
  frame-mobile: 390px
  cover-album: 240px
  cover-musica: 200px
  cover-feed: 64px
  cover-row: 48px
  avatar: 36px
  avatar-profile: 128px
  avatar-profile-mobile: 96px
  admin-side: 220px
components:
  top-bar:
    backgroundColor: '{colors.bg}'
    textColor: '{colors.fg}'
    typography: '{typography.nav}'
    padding: 14px 32px
    height: auto
  top-bar-staff:
    backgroundColor: '{colors.bg}'
    padding: 14px 32px
  search-field:
    backgroundColor: '{colors.card}'
    textColor: '{colors.fg}'
    rounded: '{rounded.full}'
    padding: 9px 16px
    typography: '{typography.meta}'
  search-panel:
    backgroundColor: '{colors.card}'
    rounded: '{rounded.xl}'
    padding: 8px
    width: 600px
  search-result-row:
    padding: 8px 12px
    rounded: '{rounded.md}'
    typography: '{typography.item-title}'
  button-primary:
    backgroundColor: '{colors.coral}'
    textColor: '{colors.on-coral}'
    typography: '{typography.button}'
    rounded: '{rounded.full}'
    padding: 11px 22px
  button-secondary:
    backgroundColor: transparent
    textColor: '{colors.fg}'
    rounded: '{rounded.full}'
    padding: 11px 22px
  button-small:
    padding: 6px 14px
    typography: '{typography.caption}'
  stars:
    textColor: '{colors.coral}'
    backgroundColor: '{colors.star-empty}'
    typography: '{typography.stars-md}'
  chip:
    backgroundColor: '{colors.card}'
    textColor: '{colors.fg}'
    rounded: '{rounded.full}'
    padding: 5px 14px
    typography: '{typography.meta}'
  chip-selected:
    backgroundColor: '{colors.coral-tint}'
    rounded: '{rounded.full}'
  card:
    backgroundColor: '{colors.card}'
    rounded: '{rounded.md}'
    padding: 16px
  card-auth:
    backgroundColor: '{colors.card}'
    rounded: '{rounded.xl}'
    padding: 22px 24px
    width: 420px
  cover:
    backgroundColor: '{colors.card}'
    rounded: '{rounded.sm}'
  input:
    backgroundColor: transparent
    textColor: '{colors.fg}'
    rounded: '{rounded.md}'
    padding: 11px 14px
  alert-error:
    backgroundColor: '{colors.err-bg}'
    textColor: '{colors.fg}'
    rounded: '{rounded.md}'
    padding: 12px 14px
  track-row:
    padding: 6px 8px
  feed-item:
    padding: 20px 0
  profile-tab:
    typography: '{typography.tab}'
  tab-bar-mobile:
    backgroundColor: '{colors.bg}'
    padding: 12px 8px
  admin-table:
    typography: '{typography.meta}'
  admin-side-link:
    rounded: '{rounded.md}'
    padding: 10px 12px
  avatar-menu:
    backgroundColor: '{colors.card}'
    textColor: '{colors.fg}'
    rounded: '{rounded.lg}'
    padding: 6px
    width: 220px
    typography: '{typography.nav}'
  avatar-menu-item:
    rounded: '{rounded.md}'
    padding: 9px 12px
  follow-list-item:
    padding: 12px 0
    typography: '{typography.item-title}'
  badge-staff:
    backgroundColor: '{colors.coral}'
    textColor: '{colors.on-coral}'
    rounded: '{rounded.full}'
    padding: 3px 10px
---

## Brand & Style

O musicboxd e um diario social de musica: nota, review e amigos. A marca ja existe em `musicboxd-brand/` (logo, disco, favicons, paleta) e este documento a herda, sem redefinir. O tom visual e de "noite com um disco de vinil": fundo Noite, texto Papel e uma unica cor de destaque, o Coral do disco. O texto do logo ja e vetor, entao nao depende de Outfit estar instalada.

Titulos em Outfit Bold, texto corrido em Inter. Superficies planas e sobrias; a personalidade vem da cor Coral usada com economia (estrelas, acao principal, foco, item ativo) e do disco/logo. Nao ha ilustracao decorativa nem gradientes de superficie (o unico gradiente e o preenchimento das estrelas e o placeholder de capa/banner).

Tema escuro e o padrao; o tema claro e alternavel. Idioma da interface: pt-BR.

Logo: no tema escuro usa `musicboxd-logo-dark` (texto claro, disco coral); no tema claro usa `musicboxd-logo-light` (disco preto; nos mocks, `musicboxd-logo-light@2x.png`). O logo e sempre link para o Feed.

## Colors

Tokens no frontmatter em duas familias: escuro (sem sufixo, padrao) e claro (`-light`). Nomes semanticos, um valor por tema.

- **Coral (`{colors.coral}` `#FF5A3C`)** e a unica cor da marca. Usado em: preenchimento das estrelas, botao primario, foco visivel (anel), item ativo (aba do perfil, menu lateral admin, borda do resultado destacado), termo casado na busca, links de acao no feed ("Ver album"), selo Staff, indicador de review, hover de links/linhas. Nunca para fundos grandes, texto longo ou decoracao.
- **Texto sobre Coral (`{colors.on-coral}` `#111317`)** em qualquer preenchimento coral, nos dois temas.
- **Noite / Papel**: `{colors.bg}` `#111317` e `{colors.fg}` `#F3EFE8` no escuro; invertidos no claro (`{colors.bg-light}` `#F3EFE8`, `{colors.fg-light}` `#111317`).
- **Card (`{colors.card}` `#1a1d22` / `{colors.card-light}` `#fbf9f5`)**: superficie elevada por tom (cartoes, campo de busca, painel, placeholders de capa, chips, tabela).
- **Muted (`{colors.muted}` .62 / `{colors.muted-light}` .66)**: texto secundario (metadados, datas, rotulos, placeholders).
- **Line (`{colors.line}` .14 / `{colors.line-light}` .16)**: bordas e divisores de 1px.
- **Estrela vazia (`{colors.star-empty}` .24 / `{colors.star-empty-light}` .22)**: parte nao preenchida da estrela.
- **Erro**: `{colors.err}` `#FF8A73` (escuro) e `{colors.err-light}` `#B3260E` (claro) para texto/borda de erro; `{colors.err-bg}` `rgba(255,90,60,.12)` (escuro) e `{colors.err-bg-light}` `rgba(255,90,60,.10)` (claro) como fundo de alerta. Criados no mock de auth e reutilizados no admin (erro de upload de capa).
- **Tintas de Coral**: `{colors.coral-tint}` (chip selecionado), `{colors.coral-tint-hover}` (hover de resultado), `{colors.coral-tint-active}` (resultado ativo por teclado), `{colors.focus-ring}` (anel de foco de 3px em campos).
- **Coral de texto, tema claro (`{colors.coral-text-light}` `#C23516`)**: versao mais escura do Coral para texto pequeno e links no tema claro ("Ver album", "Ver musica", termo casado na busca, hover de links/linhas, rotulo de aba/item ativo em texto). Contraste calculado (WCAG, luminancia relativa): 4,80:1 sobre Papel `#F3EFE8` e 5,24:1 sobre card claro `#fbf9f5` (AA 4,5:1 atendido). `#FF5A3C` sobre Papel da so 2,70:1, por isso nao serve para texto pequeno no claro. No tema escuro nao ha token novo: `{colors.coral}` sobre Noite passa (6,0:1, medido). [ASSUMPTION] Valor escolhido pela facilitadora conforme decisao aprovada (coral mais escuro so em textos/links pequenos).
- **Branco (`{colors.brand-white}`)**: somente selo do disco no logo, nao e cor de UI.

`{colors.coral}` `#FF5A3C` segue em botoes, estrelas, selo Staff, anel de foco, sublinhado de aba e demais destaques graficos ou preenchimentos (nao e texto). Somente texto pequeno e links coral no tema claro usam `{colors.coral-text-light}`.

Valores das tintas coral sao usados como no mock e nao tem versao clara propria (mesmo valor nos dois temas).

Mostrar erro nunca depende so de cor: texto, icone "!" ou aviso no topo do cartao acompanham (ver EXPERIENCE.md, Accessibility Floor).

Contraste medido: `#FF5A3C` sobre Papel `#F3EFE8` = 2,70:1 (adequado so para estrelas e elementos graficos, com apoio de texto/valor; texto grande em negrito seria limite). Texto e links pequenos coral no claro usam `{colors.coral-text-light}` (4,80:1). Coral sobre card claro `#fbf9f5` = 2,95:1, mesma regra.

## Typography

Duas familias, carregadas do Google Fonts: Outfit Bold 700 (titulos, nomes, botoes, abas, cabecalhos de grupo) e Inter 400/500/600 (texto corrido, navegacao, metadados).

- `{typography.display}` 44px: titulo do album (mobile 34px). `{typography.h1-page}` 34px: titulos de pagina (Feed, musica, nome do perfil; mobile 28px no nome, 22px no titulo de auth).
- `{typography.h1-auth}` 24px: "Entrar" e titulos do cartao de auth, mais enxuto por decisao do usuario. `{typography.h1-admin}` 30px e `{typography.h2-admin}` 20px no admin.
- `{typography.h2}` 22px: titulos de secao ("Faixas", "Amigos que avaliaram").
- `{typography.item-title}` 17px Outfit: titulo de item em feed, lista e resultado (15px no resultado da busca).
- `{typography.label-caps}` 12px caixa alta com espacamento: cabecalho de grupo do painel de busca (Albuns, Musicas, Pessoas) e legenda do menu lateral admin.
- `{typography.tab}` 16px Outfit: abas do perfil. `{typography.button}` 15px Outfit: botoes (16px em auth, 14px em botao pequeno).
- `{typography.body}` Inter 16px, linha 1.5. `{typography.nav}` 15px/500 nos links da barra. `{typography.meta}` 14px e `{typography.caption}` 13px para metadados, datas, dicas.
- Numeros de duracao, contagens e valores de nota usam `font-variant-numeric: tabular-nums`.
- Estrelas: glifo ★ em Inter, `letter-spacing: 2px`, tamanhos `stars-sm` 18px (linha de faixa, resultado), `stars-md` 20px, `stars-lg` 30px (pagina da musica).

Titulos e textos tem caixa de frase; caixa alta so nos rotulos `label-caps` e cabecalhos de tabela admin (`{typography.th-admin}`).

## Layout & Spacing

Escala base: 4 / 8 / 12 / 16 / 24 / 32 / 40 / 64 px (`{spacing.1}` a `{spacing.8}`); os mocks tambem usam 14, 20 e 28 em ajustes finos, que nao viram tokens.

Desktop-first. Quadro de referencia 1280px; mobile 390px.

- **Largura de conteudo**: `{spacing.content-max}` 960px (album, perfil), `{spacing.content-narrow}` 720px (feed, pagina da musica). Centralizado; gutter `{spacing.gutter-desktop}` 32px (mobile `{spacing.gutter-mobile}` 16px).
- **Barra do topo**: linha unica, padding 14px 32px, gap 24px; logo 132px a esquerda, campo de busca flexivel (max 520px), links a direita. No mobile: logo 110px + avatar na primeira linha, busca em linha propria de largura total (ordem 3), links escondidos.
- **Pagina**: coluna vertical com gap 40px entre secoes (mobile 32px/28px no perfil), padding topo 40px.
- **Hero de album**: capa `{spacing.cover-album}` 240px + metadados, alinhados a base, gap 32px. Mobile: coluna, capa em largura total. Pagina da musica: capa `{spacing.cover-musica}` 200px.
- **Linha de faixa**: grade `32px 1fr 56px 130px` (numero, titulo, duracao, estrelas). Mobile: `22px 1fr 44px` com estrelas na segunda linha, colunas 2-3.
- **Amigos**: grade 2 colunas gap 12px (mobile 1 coluna).
- **Favoritos do perfil**: albuns em grade de 5 colunas gap 16px (mobile 3); "Ouvir depois" em grade de 5 colunas gap 20/16 (mobile 2); musicas favoritas e avaliadas em lista de linhas.
- **Perfil**: capa/banner 200px de altura (mobile 130px) com foto `{spacing.avatar-profile}` 128px sobreposta (margem negativa -64px; mobile 96px/-48px), borda de 4px na cor de fundo. Menu de abas centralizado abaixo de seguidores/seguindo.
- **Admin** (somente desktop, 1280px): barra do topo minima + grade `{spacing.admin-side}` 220px de menu lateral e area principal (padding 32px 40px, gap 24px).
- **Barra de abas mobile**: 4 itens distribuidos (space-around), padding 12px 8px, borda superior de 1px.

## Elevation & Depth

Hierarquia por tom e borda, nao por sombra. Cartoes (`{colors.card}`) sobre `{colors.bg}` com borda `{colors.line}` de 1px. Sombras so em camadas flutuantes e em estado de arrasto:

- Painel de busca: `0 16px 48px rgba(0,0,0,.47)`, z-index 20.
- Menu suspenso "Avaliacoes": borda + fundo card, sem sombra de destaque, z-index 10.
- Item arrastado (favoritos no editar perfil): `0 10px 24px rgba(0,0,0,.35)`, deslocado 2-4px para cima; capa com borda coral.
- Moldura dos mocks (`0 8px 40px`) e apresentacao, nao faz parte do produto.

## Shapes

- `{rounded.full}` (pilula): botoes, campo de busca, chips, selo Staff, controle segmentado, avatar (circulo), botoes de icone circulares.
- `{rounded.xl}` 12px: cartao de auth, painel de busca. `{rounded.lg}` 10px: banner do perfil, menu suspenso.
- `{rounded.md}` 8px: cartoes, campos de formulario, alertas, linha de resultado, link do menu lateral admin, cartao de amigo.
- `{rounded.sm}` 6px: capas de album, campo de texto de review, slot vazio. `{rounded.xs}` 4px: capas pequenas (feed, resultado, miniatura admin).
- Capas seguem o raio do container; o avatar e sempre circulo.

## Components

**Barra do topo (usuario logado).** Logo (link para o Feed) . campo de busca sempre visivel . "Ouvir depois" . "Avaliacoes ▾" (menu com Albuns / Musicas) . avatar 36px com borda `{colors.coral}` (abre o menu do avatar; `aria-label="Menu da conta"`). Nao ha item "Feed" (o logo cumpre o papel). Link ativo/hover em coral. Padding `{components.top-bar.padding}`.

**Barra do topo (visitante).** Logo . busca . botoes "Entrar" (secundario pequeno) e "Criar conta" (primario pequeno, padding 8px 18px) alinhados a direita; sem "Ouvir depois", "Avaliacoes" nem avatar.

**Barra do topo Staff (`{components.top-bar-staff}`).** Somente logo e selo "Staff" (`{components.badge-staff}`: Outfit 11px caixa alta, espacamento .08em). Sem busca, "Ouvir depois", "Avaliacoes", perfil/avatar. Vale so para `/admin`.

**Barra de abas mobile (`{components.tab-bar-mobile}`).** Buscar . Ouvir depois . Avaliacoes . Perfil. Outfit 13px/600, `{colors.muted}`; aba ativa em `{colors.coral}`. Fundo `{colors.bg}`, borda superior `{colors.line}`.

**Estrelas (`{components.stars}`).** Cinco glifos ★ com preenchimento parcial por gradiente linear 90deg: coral ate a porcentagem da nota, `{colors.star-empty}` depois (nota 4,5 = 90%, meia estrela suportada visualmente). Somente leitura (span) ou interativa (button), tamanhos 18 / 20 / 30. Ao lado, valor numerico Outfit 20px em virgula decimal ("4,5") na "Sua nota"; estado sem nota mostra as cinco estrelas vazias e o texto "Sem nota". Alvo de clique de cada estrela e dividido em metade esquerda/direita.

**Botao primario (`{components.button-primary}`).** Fundo coral, texto `{colors.on-coral}`, pilula, Outfit 15px. Variante pequena (`.s`/`sm`): 8px 18px, 14px. **Secundario**: transparente, borda 1,5px `{colors.fg}`. Desabilitado: opacidade .5, cursor not-allowed. Foco visivel: contorno 2px coral com offset 3px.

**Chip (`{components.chip}`).** Pilula, borda `{colors.line}`, fundo card, 14px. Selecionado/ativo: borda coral e fundo `{colors.coral-tint}` com peso 600 (`{components.chip-selected}`). Chip de genero removivel: em modo de edicao mostra "×" muted (hover coral) e borda `{colors.muted}`. Chip de sugestao ("+ Jazz"): hover coral. Chips de genero do admin alternam selecionado/nao.

**Cartao (`{components.card}`).** Fundo card, borda `{colors.line}`, raio 8px, padding 16px. Variante auth `{components.card-auth}`: 420px, raio 12px, padding 22px 24px (18px no mobile), centralizado sob o logo grande (260px; 200px mobile). Variante nota informativa (`gnote`): mesma superficie, padding 12px 16px, 14px muted com trecho em `{colors.fg}` negrito.

**Cartao de item de feed (`{components.feed-item}`).** Avatar 36 . linha "Nome avaliou o album/a musica · ha 12 min" (nome 15px/600, resto muted 14px) . capa 64px (56px mobile) + titulo Outfit 17px + artista muted + estrelas . texto da review opcional (15px) . link coral "Ver album"/"Ver musica" (14px/600). Separador de 1px, padding vertical 20px. "Carregar mais" centralizado ao fim.

**Linha de faixa (`{components.track-row}`).** Numero muted tabular . nome (link, hover coral, 500) . duracao muted alinhada a direita (faixa sem duracao fica em branco) . estrelas 18px clicaveis. Bordas inferior de 1px.

**Menu do avatar (`{components.avatar-menu}`).** Abre ao clicar no avatar da barra do topo, ancorado a direita sob o avatar, 220px, fundo card, borda `{colors.line}`, raio 10px, padding 6px, sem sombra de destaque, z-index 10 (mesmo padrao do menu "Avaliacoes"). Itens (`{components.avatar-menu-item}`, Inter 15px/500, raio 8px, padding 9px 12px): Meu perfil, Editar perfil, Tema claro/escuro (item com rotulo da acao, ex. "Tema claro" quando escuro), divisor de 1px `{colors.line}`, Sair. Hover: fundo `{colors.coral-tint-hover}`; foco: contorno 2px coral. No mobile o avatar abre o mesmo menu. [ASSUMPTION] Composicao de itens e visual propostos pela facilitadora e aprovados como padrao.

**Item de lista de seguidores/seguindo (`{components.follow-list-item}`).** Linha: avatar 48px (`{spacing.cover-row}`) . nome Outfit 17px + @usuario muted 14px . botao pequeno "Seguir"/"Seguindo" a direita (estados em EXPERIENCE.md). Separador de 1px `{colors.line}`, padding vertical 12px, dentro da largura `{spacing.content-narrow}`. Sem botao na propria linha do usuario logado. [ASSUMPTION] Padrao proposto.

**Botao Seguir (estados).** "Seguir": `{components.button-primary}` pequeno. "Seguindo": secundario pequeno; no hover/foco o rotulo vira "Deixar de seguir". Carregando: opacidade .5, cursor not-allowed, sem mudar a largura. [ASSUMPTION]

**Cartao de amigo.** Avatar 36 . nome 600 . estrelas somente leitura; fundo card, raio 8px, padding 12px 14px. **Review**: avatar, nome 600, data muted 13px, texto abaixo (gap 6px).

**Campo de review.** Caixa card com textarea (raio 6px, sem redimensionar), dica muted 13px e botao "Publicar" a direita (mobile: coluna, botao em largura total).

**Campo de busca (`{components.search-field}`).** Pilula, fundo card, borda `{colors.line}`, 14px, placeholder muted. Foco: borda coral + anel `{colors.focus-ring}` 3px. Campo focado e vazio mostra so o placeholder, sem painel.

**Painel de busca (`{components.search-panel}`).** Ancorado sob o campo, 600px (mobile: largura total da tela, cantos inferiores arredondados, sem bordas laterais), fundo card, raio 12px, padding 8px, sombra flutuante. Grupos com cabecalho `label-caps`: Albuns, Musicas, Pessoas. **Linha de resultado (`{components.search-result-row}`)**: capa 44px (musica 36px) ou avatar 40px + titulo Outfit 15px (termo casado em coral negrito) + subtitulo muted 13px (artista, ano; `@usuario`) + metadado a direita muted 13px (duracao/nota; escondido no mobile). Hover: fundo `{colors.coral-tint-hover}`. Ativo por teclado: fundo `{colors.coral-tint-active}` e borda esquerda 3px coral. Carregando: skeleton (barras com gradiente animado `line`/`star-empty`, animacao desligada em `prefers-reduced-motion`). Sem resultados: titulo Outfit 17px + texto muted 14px centralizados, padding 28px 20px.

**Aba do perfil (`{components.profile-tab}`).** Outfit 16px, muted; ativa em `{colors.fg}` com sublinhado de 3px coral (margem -1px sobre a borda do container). Menu centralizado; rola horizontalmente no mobile sem barra visivel. Foco visivel: contorno 2px coral.

**Cabecalho do perfil.** Banner 200px (raio 10px, gradiente placeholder card->line quando sem capa), foto circular 128px sobreposta, nome 34px, @usuario muted 15px, botao (Seguir / Editar perfil), bio (max 640px), contagens "128 seguidores / 94 seguindo" (numero Outfit 16px em `{colors.fg}`, rotulo muted).

**Slot vazio.** Borda tracejada 1,5px `{colors.line}`, raio 6px, texto muted 13px, mesmo tamanho da capa de favorito (quadrado, largura da coluna); hover coral. Em musicas favoritas: linha com botao tracejado "+ Escolher musica" altura 44px. No editar perfil: botao de remover (X) circular 32px no canto da capa; alca de arrastar de 6 pontos.

**Campos e alertas de formulario (`{components.input}`).** Rotulo 14px/600 acima, campo raio 8px, borda `{colors.line}`, padding 11px 14px, 15px. Foco: contorno 2px coral. Erro: borda `{colors.err}`, mensagem `{colors.err}` 13px/500 abaixo. Alerta no topo do cartao (`{components.alert-error}`): fundo `{colors.err-bg}`, borda `{colors.err}`, texto em negrito de titulo na cor de erro. Campo de senha: botao de icone de olho (34px) a direita, alternando olho/olho cortado. Campo de usuario com prefixo "@" muted a esquerda. Dica de campo muted 13px.

**Botao de icone circular.** 32px, borda `{colors.line}`, fundo card; hover coral. Lapis de generos: ativo = fundo coral + icone `{colors.on-coral}`.

**Estado vazio.** Coluna centralizada, max 520px, padding 72px 24px: icone 72px muted, titulo, texto muted, ate 2 botoes lado a lado.

**Tabela admin (`{components.admin-table}`).** Largura total, colapso de bordas, 14px. Cabecalho: `{typography.th-admin}` muted caixa alta, borda inferior `{colors.line}`. Celulas padding 10px 12px, borda inferior 1px, alinhamento vertical central; colunas numericas a direita tabulares; coluna Acoes a direita sem quebra. Miniatura de capa 40px (raio 4px). Linha oculta: celulas (exceto Acoes) em opacidade .55. Selo de status (`pill`): pilula com ponto de 7px, "Visivel" (ponto coral) ou "Oculto" (muted). Acoes como links sublinhados 14px/600 ("Editar", "Ocultar", "Reexibir"; hover coral). Acima: filtro pilula 320px e contador "8 de 214 albuns" muted.

**Menu lateral admin.** 220px, borda direita, legenda "Administracao" (12px caixa alta muted), links 15px/500 raio 8px; ativo com fundo card, borda esquerda 3px coral e peso 600.

**Editor de album (admin).** Grade `200px 1fr`, gap 28px. Upload de capa: quadrado 200px tracejado 2px; estado com imagem: borda solida; estado erro: borda e fundo `{colors.err}`/`{colors.err-bg}` com mensagem "!" em circulo. Controle segmentado Visivel/Oculto (`seg`: pilula com borda 1,5px, opcao ativa em coral). Linha de faixa editavel: grade `28px 28px 1fr 96px 84px` (alca, numero, nome, duracao mm:ss, acao); faixa oculta com campo em opacidade .5; botao de slot tracejado "+ Adicionar faixa".

## Do's and Don'ts

| Do | Don't |
|---|---|
| Coral como unica cor de destaque, em estrelas, acao principal, foco e item ativo | Segundo tom de destaque, fundos coral grandes, texto longo em coral |
| Texto sobre coral sempre `{colors.on-coral}` | Texto branco sobre coral |
| Logo dark no escuro, logo light (disco preto) no claro | Recolorir o logo ou usar o disco coral sobre Papel |
| Hierarquia por tom (`{colors.card}`) e borda de 1px | Sombras em cartoes e listas; gradientes de superficie |
| Estrelas com preenchimento parcial (meia nota) e valor numerico com virgula ("4,5") | Mostrar media da comunidade ou media das faixas na pagina do album |
| Placeholder neutro quando falta capa, foto ou banner, no mesmo tamanho | Deixar buraco no layout ou esconder o bloco por falta de imagem |
| Erro com texto + borda/aviso, nao so cor (`{colors.err}` por tema) | Erro so em vermelho, ou vermelho puro diferente dos tokens |
| Foco visivel em coral (contorno 2px, ou anel 3px em campos) | Remover outline sem substituto |
| Slot vazio e capa com exatamente o mesmo tamanho | Slot menor que os itens preenchidos |
| Barra do topo Staff so com logo e selo | Levar busca, "Ouvir depois" ou avatar ao admin |
| Coral escuro `{colors.coral-text-light}` para texto pequeno e links no tema claro (AA 4,80:1) | `#FF5A3C` em texto pequeno sobre Papel (2,70:1) |
| Numeros tabulares em duracao, contagens e notas | Numeros proporcionais em colunas |

## Mockups de referencia

Veja a tabela de mocks em [EXPERIENCE.md](EXPERIENCE.md#mockups-de-referencia). Os mocks estao em `mockups/`; este documento vence em caso de conflito.
