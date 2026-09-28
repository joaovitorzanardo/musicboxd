---
name: Musicboxd
status: final
updated: 2026-09-26
sources:
  - _bmad-output/planning-artifacts/prds/prd-teste-2026-09-25/prd.md
  - _bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-teste-2026-09-26/DESIGN.md
---

# Musicboxd - Experience Spine

> Onde este documento diverge do PRD ou da arquitetura, ele vence para UX e a divergencia esta listada em "Decisoes de produto pendentes". Mocks HTML aprovados ficam em `.working/` (key-album, key-feed, key-perfil, key-musica, key-auth, key-admin, key-busca) e sao ilustracao; o spine vence em caso de conflito.

## Foundation

- **Form-factor:** web responsivo, desktop-first (desktop 1280 e mobile 390; peso maior no desktop). React SPA (Vite, sem SSR). Um app mobile futuro usa a mesma API, fora do escopo aqui.
- **Sistema de UI:** nenhuma biblioteca de UI definida. Os tokens do DESIGN.md sao a base; componentes serao construidos sobre eles. [ASSUMPTION] Escolha de lib (se houver) fica para a implementacao e deve so mapear os tokens.
- **Tema:** escuro por padrao, com alternancia para claro. Identidade em `DESIGN.md`.
- **Idioma:** pt-BR. Decimal com virgula ("4,5"), tempos "há 12 min".
- **Stakes:** hobby com deploy real; perfis e Ouvir depois sao publicos.
- **Papeis:** Visitante (le tudo, age -> prompt de cadastro), Usuario logado, Staff (area `/admin`).

## Information Architecture

Nomenclatura de UI: "Ouvir depois" e o nome exibido da Listenlist do PRD; "Avaliacoes" e o menu do topo.

| Tela | Rota | Quem acessa | Notas |
|---|---|---|---|
| Feed | `/` | Logado (visitante: ver Estados) | Avaliacoes de albuns e musicas de quem o usuario segue, mais novas primeiro; cada item mantem o texto da review (decisao do usuario) |
| Album | rota por id de album | Todos (leitura); logado age | Capa/meta, sua nota, Ouvir depois, faixas, sua review, amigos, reviews de outros |
| Musica | rota por id de musica | Todos (leitura); logado avalia | So nome, artista, album, duracao e a nota do usuario |
| Perfil | `/u/:usuario` (tab Perfil) | Todos | Cabecalho fixo + tabs |
| Perfil > Ouvir depois | `/u/:usuario/ouvir-depois` | Todos | Publico |
| Perfil > Musicas | `/u/:usuario/musicas` | Todos | Musicas avaliadas |
| Perfil > Albuns | `/u/:usuario/albuns` | Todos | Albuns avaliados |
| Perfil > Seguidores | `/u/:usuario/seguidores` [ASSUMPTION] | Todos | Lista de perfis que seguem o usuario; botao Seguir por linha. Aberta pela contagem "seguidores" do cabecalho |
| Perfil > Seguindo | `/u/:usuario/seguindo` [ASSUMPTION] | Todos | Lista de perfis que o usuario segue; botao Seguir por linha. Aberta pela contagem "seguindo" |
| Editar perfil | rota propria (nao definida) [ASSUMPTION] | Dono | Tela separada, nao inline |
| Login | rota propria (nao definida) [ASSUMPTION] | Visitante | Sem barra do topo |
| Cadastro | rota propria (nao definida) [ASSUMPTION] | Visitante | Usuario, email, senha |
| Verifique seu email | rota propria (nao definida) [ASSUMPTION] | Apos cadastro | Com reenviar |
| Admin > Catalogo | `/admin` (subrota nao definida) | Staff | Lista, editar album, novo album |
| Admin > Generos | `/admin` (subrota nao definida) | Staff | Lista controlada |

Nomes de rota do Perfil vem do mock ("/u/:usuario, /ouvir-depois, /musicas, /albuns") e do memlog; `/seguidores` e `/seguindo` sao padrao proposto [ASSUMPTION] (nao sao tabs: a lista abre como pagina propria com "← Voltar ao perfil"). Demais rotas ficam a definir na implementacao.

**Sem pagina de busca.** Toda a busca acontece pelo campo da barra do topo, com resultados em dropdown. Isso substitui a paginacao numerada de busca do PRD/arquitetura (ver pendencias).

**Perfil com tabs.** Cabecalho fixo (capa, foto, nome, @, bio, seguidores/seguindo, botao) em todas as tabs; abaixo, menu de tabs centralizado: Perfil (generos, 5 albuns favoritos, 5 musicas favoritas), Ouvir depois, Musicas (avaliadas), Albuns (avaliados). Cada tab tem rota propria.

**Barra do topo (desktop, logado):** logo (link para o Feed; nao ha item "Feed") . busca sempre visivel . Ouvir depois . Avaliacoes ▾ (Albuns / Musicas do proprio perfil) . avatar (abre o menu do avatar; ver Component Patterns). Visitante: logo . busca . Entrar . Criar conta. **Barra do Staff** (so `/admin`): logo + selo Staff, sem busca, Ouvir depois, Avaliacoes ou avatar.

**Barra de abas (mobile):** Buscar . Ouvir depois . Avaliacoes . Perfil. Busca no mobile: campo em linha propria na barra do topo. [ASSUMPTION] A aba "Buscar" foca o campo de busca do topo (o mock mostra a aba, nao o comportamento).

**Mapa de acesso**
- Visitante: Feed [ver Estados], Album, Musica, Perfil e tabs, Login, Cadastro. Nao age (nota, review, seguir, Ouvir depois): prompt de cadastro.
- Logado: tudo acima, mais editar o proprio perfil, avaliar, review, seguir, Ouvir depois. Nao acessa `/admin`.
- Staff: `/admin` (Catalogo, Generos). [NOTE FOR UX] Se o Staff tambem usa as telas de usuario com a mesma conta, e como alterna, nao esta definido.

## Voice and Tone

Microcopy usada nos mocks. Voz da marca em DESIGN.md (Brand & Style). Direto, curto, pt-BR informal-neutro, sem exclamacao, sem gamificacao.

| Situacao | Texto |
|---|---|
| Busca, placeholder | "Buscar" |
| Sem resultado de busca | "Nada encontrado para "xyz"" / "Confira a grafia ou tente outro nome de álbum, música ou pessoa." |
| Feed vazio | "Você ainda não segue ninguém" / "Quando você seguir pessoas, o que elas avaliarem aparece aqui. Busque amigos e álbuns para começar." Botoes: "Buscar pessoas", "Buscar álbuns" |
| Item de feed | "{Nome} avaliou o álbum" / "avaliou a música" . "há 12 min" . link "Ver álbum" / "Ver música" . "Carregar mais" |
| Nota | "Sua nota" . "Sem nota" . "Limpar nota" . dica "Clique na metade esquerda ou direita de uma estrela para dar meia nota." |
| Ouvir depois | "+ Adicionar a Ouvir depois" |
| Review | "Sua review" . "Sua review aparece na página do álbum." . "Publicar" |
| Album | "Faixas" . "Amigos que avaliaram" . "Reviews de outras pessoas" . "← Voltar ao álbum" |
| Visitante em ato | "Entre para avaliar esta música." . botoes "Entrar" / "Criar conta" . "Seguir leva ao cadastro. Tudo neste perfil é público e pode ser lido sem conta." |
| Perfil vazio de tab | "Nada por aqui ainda" / "Rafael ainda não salvou álbuns para ouvir depois." . Musicas: "Rafael ainda não avaliou músicas." . Albuns: "Rafael ainda não avaliou álbuns." . proprio perfil: "Você ainda não avaliou músicas." / "Você ainda não avaliou álbuns." + botao "Buscar álbuns" [ASSUMPTION] |
| Seguir | "Seguir" . "Seguindo" (hover/foco: "Deixar de seguir") . lista: "Seguidores" / "Seguindo" . vazio: "Ninguém por aqui ainda" / "{Nome} ainda não tem seguidores." ou "{Nome} ainda não segue ninguém." [ASSUMPTION] |
| Menu do avatar | "Meu perfil" . "Editar perfil" . "Tema claro" / "Tema escuro" (rotulo da acao) . "Sair" [ASSUMPTION: textos] |
| Review propria | "Editar" . "Apagar" . "Salvar" . "Cancelar" . confirmacao "Apagar sua review?" / "O texto será removido. Sua nota continua." . "Apagar review" / "Cancelar" [ASSUMPTION] |
| Album, estados | "Nenhum amigo avaliou ainda" / "Quando alguém que você segue avaliar este álbum, aparece aqui." . "Salvo em Ouvir depois" . "Remover de Ouvir depois" [ASSUMPTION] |
| Contagens de perfil | "128 seguidores" . "94 seguindo" . "24 álbuns salvos" . "214 músicas · mais novas primeiro" |
| Editar perfil | "As mudanças só aparecem no seu perfil depois de salvar." . "Trocar capa" . "Trocar foto" . "JPG ou PNG" . "Editar gêneros" . "Adicionar gênero" . "Só é possível escolher gêneros da lista mantida pelo Staff." . "Arraste para reordenar; o × remove" . "Soltar aqui" . "Escolher álbum" . "+ Escolher música" . "Salvar" . "Cancelar" |
| Login | Titulo "Entrar" . "Bem-vindo de volta ao musicboxd." . "Novo por aqui? Criar conta" . erro "Email ou senha incorretos." + "Confira os dados e tente de novo." |
| Cadastro | "Só precisa de um nome de usuário, email e senha." . "Aparece no seu perfil. Letras, números, ponto e sublinhado." . "Mínimo de 8 caracteres." . "Já tem conta?" . erros "Não foi possível criar a conta." + "Corrija os campos destacados." . "Este email já está em uso." . "A senha precisa ter pelo menos 8 caracteres." |
| Verifique email | "Verifique seu email" . "Enviamos um link de confirmação para {email}. Abra o email e clique no link para ativar sua conta." . "Reenviar email" . "Não recebeu? Confira o spam ou reenvie." . "Email errado? Voltar e corrigir" . confirmacao "Email reenviado. Confira também a caixa de spam." |
| Admin | "Álbuns ocultos somem da busca, mas continuam acessíveis por link. Nada é excluído." . "+ Novo álbum" . "Filtrar catálogo" . "8 de 214 álbuns" . status "Visível" / "Oculto" . acoes "Editar", "Ocultar", "Reexibir" . "Alterações só valem depois de salvar." . "+ Adicionar faixa" . "6 faixas · 5 visíveis · arraste para reordenar" |
| Admin, capa | "JPEG, PNG ou WebP, até 5 MB. Quadrada, mínimo 600 px." . erro "Arquivo recusado" / "Formato não aceito: {arquivo}. Use JPEG, PNG ou WebP." / "Ou o arquivo passa de 5 MB (o seu tem 8,2 MB)." . "Escolher outro arquivo" |
| Admin, generos | "Lista controlada usada em álbuns e perfis. Gêneros não são excluídos, apenas ocultados." . "Novo gênero" . "Adicionar" . "Renomear" . "{n} álbuns" . "Ativo" |

Nao usar: "Listen list" (usar "Ouvir depois"), exclamacoes, mensagem de "muitas tentativas" (removida por decisao), rotulos de recuperacao de senha (nao existe).

## Component Patterns

Comportamento. Visual em DESIGN.md.Components.

| Componente | Uso | Regras de comportamento |
|---|---|---|
| Barra do topo `{components.top-bar}` | Todas as telas exceto auth e admin | Logo leva ao Feed. "Avaliacoes" abre menu (Albuns / Musicas) que leva as tabs do proprio perfil. Avatar abre o menu do avatar. Visitante ve Entrar / Criar conta. |
| Barra Staff `{components.top-bar-staff}` | `/admin` | Somente logo e selo. |
| Barra de abas mobile `{components.tab-bar-mobile}` | Mobile, telas de usuario | 4 abas; a da tela atual fica em coral. |
| Estrelas `{components.stars}` | Album, faixa, musica, feed, perfil, busca | Ver Interaction Primitives. Somente leitura em feed, amigos, perfil e busca. |
| Linha de faixa `{components.track-row}` | Album | Estrelas clicaveis na propria linha; clicar no nome abre a Musica, onde tambem se avalia. Faixa sem duracao fica em branco. |
| Campo de review | Album | Uma review por usuario por album (arquitetura AD-6), escrita na propria pagina do album, sem tela separada. Publicar publica. **Editar/apagar a propria review (FR-7), na pagina do album, sem tela separada:** depois de publicada, a review propria aparece no lugar do campo com a nota, a data e as acoes "Editar" e "Apagar". "Editar" volta ao campo preenchido com "Salvar" e "Cancelar" (Cancelar descarta a edicao). "Apagar" abre confirmacao (dialogo simples: "Apagar sua review?", "Apagar review" em botao primario, "Cancelar" secundario; Esc e clique fora cancelam; foco inicial em Cancelar). Apagar remove so o texto e mantem a nota do album; depois o campo vazio volta. [ASSUMPTION] Padrao proposto e aprovado. |
| Ouvir depois (botao) | Album | Album ja avaliado nao entra em Ouvir depois (regra do PRD); avaliar remove da lista. **Estados do botao (padrao aprovado, detalhes [ASSUMPTION]):** (1) nao avaliado e nao salvo: "+ Adicionar a Ouvir depois". (2) ja salvo: estado "Salvo" ("Salvo em Ouvir depois", com check) e opcao de remover (hover/foco mostra "Remover de Ouvir depois"; remover volta ao estado 1). (3) album ja avaliado (nota dada): botao oculto, conforme regra do PRD FR-8 (album avaliado nao entra em Ouvir depois). Visitante: clique leva ao cadastro. Carregando: botao desabilitado sem mudar a largura. |
| Botao Seguir | Cabecalho do perfil e linhas das listas de seguidores/seguindo | Estados: "Seguir" (primario) . "Seguindo" (secundario; em hover ou foco de teclado o rotulo muda para "Deixar de seguir"; clicar deixa de seguir sem confirmacao) . carregando (desabilitado, opacidade .5, largura fixa, `aria-busy`). Visitante: leva ao cadastro. Na lista, o proprio usuario logado nao tem botao na sua linha. Falha de rede: volta ao estado anterior + alerta de erro curto. [ASSUMPTION] |
| Menu do avatar `{components.avatar-menu}` | Barra do topo (desktop e mobile), logado | Clique no avatar abre; itens: Meu perfil (`/u/:usuario`), Editar perfil, Tema claro/escuro (alterna e persiste; **local do toggle de tema: menu do avatar**), Sair (encerra a sessao). Esc ou clique fora fecha; setas movem entre itens; `aria-haspopup="menu"`. O avatar deixa de ser link direto ao perfil. Visitante nao tem menu. [ASSUMPTION] |
| Lista de seguidores/seguindo `{components.follow-list-item}` | `/u/:usuario/seguidores`, `/u/:usuario/seguindo` | Pagina propria com titulo, contagem e linhas (avatar, nome, @usuario, botao Seguir); linha leva ao perfil; "Carregar mais" (cursor). Publica (visitante le; Seguir leva ao cadastro). Vazio: ver State Patterns. [ASSUMPTION] |
| Cartao de feed `{components.feed-item}` | Feed | Mostra avaliacao de quem se segue; "Ver album/musica" abre o item. Paginacao por "Carregar mais" (cursor). |
| Amigos que avaliaram | Album | Lista quem o usuario segue e ja avaliou o album, com estrelas. Fica ABAIXO da area de sua nota/faixas/review (ver ordem). Nao ha media da comunidade. |
| Aba do perfil `{components.profile-tab}` | Perfil | Troca de tab muda a rota; cabecalho permanece. Menu centralizado; rola na horizontal no mobile. Estado ativo em coral. |
| Editor de favoritos | Editar perfil | 5 albuns + 5 musicas, ordem definida pelo usuario. X remove; slot vazio "Escolher album" / "+ Escolher musica" com mesmo tamanho dos itens; reordena por arrastar (alca). Sem setas. |
| Editor de generos | Editar perfil | Lapis ao lado do titulo ativa modo de edicao: cada chip ganha "×" para remover e aparece "Adicionar gênero" com sugestoes ("+ Jazz"). So generos da lista do Staff. Lapis ativo fica em coral. |
| Foto de perfil | Perfil / editar | Sobreposta a capa (capa atras). Em edicao, "Trocar capa" e "Trocar foto" nao se sobrepoem. |
| Painel de busca `{components.search-panel}` | Barra do topo | Ver Interaction Primitives. Publico (visitante tambem busca). |
| Cartao de auth `{components.card-auth}` | Login, cadastro, verifique email | Logo grande centralizado acima; sem barra do topo. Titulo enxuto. Senha com botao de olho (`aria-label` "Mostrar senha"/"Ocultar senha"). So email + senha (cadastro tambem usuario); sem confirmar senha; sem "esqueci a senha". |
| Tabela admin `{components.admin-table}` | Admin > Catalogo | Filtro por texto; "Ocultar"/"Reexibir" alterna visibilidade, nunca exclui. Oculto some da busca mas segue acessivel por link. |
| Editor de album (admin) | Admin | Capa por upload (validacao de tipo/tamanho), generos por chips da lista controlada, faixas editaveis reordenaveis por arrastar, faixa ocultavel, "Salvar"/"Cancelar"; alteracoes so valem apos salvar. |
| Lista de generos (admin) | Admin > Generos | Adicionar, renomear (edicao inline), ocultar; ocultar retira das opcoes mas mantem albuns e perfis que ja usam. |

**Ordem da pagina do album (a confirmar):** capa/meta (titulo, artista, duracao total, faixas, ano) . sua nota + "Ouvir depois" . faixas . sua review . amigos que avaliaram . reviews de outras pessoas. O memlog registrou a ordem como "a confirmar".

## State Patterns

| Estado | Onde | Tratamento |
|---|---|---|
| Vazio (feed) | Feed | Icone, "Você ainda não segue ninguém", texto, botoes "Buscar pessoas" / "Buscar álbuns". |
| Vazio (tab do perfil, de outra pessoa) | Ouvir depois, Musicas, Albuns | "Nada por aqui ainda" + frase com o nome da pessoa ("Rafael ainda não avaliou músicas." / "...álbuns."). Sem botoes. Ouvir depois esta mocado; Musicas e Albuns seguem o mesmo padrao [ASSUMPTION]. |
| Vazio (tab do perfil, proprio) | Musicas, Albuns (e Ouvir depois) | "Nada por aqui ainda" + "Você ainda não avaliou músicas/álbuns." e botao "Buscar álbuns" (foca a busca do topo). [ASSUMPTION] |
| Vazio (seguidores/seguindo) | Listas | "Ninguém por aqui ainda" + "{Nome} ainda não tem seguidores." / "...não segue ninguém." [ASSUMPTION] |
| Album, nenhum amigo avaliou | Album, "Amigos que avaliaram" | A secao mantem o titulo; no lugar da grade: "Nenhum amigo avaliou ainda" + "Quando alguém que você segue avaliar este álbum, aparece aqui." (muted, sem botao). Igual para visitante. [ASSUMPTION] |
| Album, Ouvir depois | Botao Ouvir depois | Ja salvo: "Salvo" com opcao de remover; ja avaliado: botao oculto (FR-8). Ver Component Patterns. |
| Review propria | Album | Publicada: leitura com Editar/Apagar; apagar pede confirmacao. |
| Vazio (favoritos, proprio perfil) | Perfil | Slots tracejados "Escolher álbum" / "+ Escolher música". |
| Vazio (busca focada) | Campo de busca | So placeholder; nenhum painel nem sugestao. |
| Carregando (busca) | Painel | Skeleton no painel; o campo mantem o texto digitado. Animacao desligada em movimento reduzido. |
| Sem resultados | Painel | "Nada encontrado para "xyz"" + dica. |
| Carregando (demais telas) | Feed, perfil, album | [ASSUMPTION] Skeleton no mesmo padrao da busca; nao mocado. |
| Erro de credencial | Login | Alerta no topo do cartao, campos com borda de erro. Sem estado de "muitas tentativas". |
| Erro de campo | Cadastro | Mensagem junto ao campo + alerta no topo do cartao. |
| Erro de upload | Admin, capa | Zona da capa em erro, "Arquivo recusado", motivo, "Escolher outro arquivo". |
| Erro generico de rede/servidor | Todas | [ASSUMPTION] Nao mocado; usar alerta de erro do DESIGN.md com texto curto e acao de tentar de novo. |
| Visitante | Todas as telas de leitura | Barra com Entrar/Criar conta. Estrela, Seguir, Ouvir depois, review levam ao prompt de cadastro ("Entre para avaliar esta música."; Seguir leva ao cadastro). Perfil de terceiros e publico. [NOTE FOR UX] O Feed de visitante nao esta mocado; ver pendencias. |
| Oculto (catalogo) | Admin; busca; link direto | Item oculto some da busca, segue abrindo por link. No admin, linha atenuada (.55) com selo "Oculto" e acao "Reexibir". |
| Sem capa / foto / banner | Album, feed, perfil, busca, admin | Placeholder neutro no mesmo tamanho (nunca deixar buraco). Sem foto: iniciais em circulo. |
| Sem nota | Album, faixa, musica | Cinco estrelas vazias e texto "Sem nota". Aria: "Sem nota. Clique para avaliar". |
| Faixa sem duracao | Album | Campo de duracao em branco. |
| Verificacao de email | Pos-cadastro | Tela "Verifique seu email" com reenviar; apos reenviar, mensagem de confirmacao. |
| Foco | Campos, botoes, abas | Contorno coral visivel. |

## Interaction Primitives

- **Estrelas.** Nota 0 a 5 em passos de 0,5. Cada estrela tem metade esquerda e direita clicaveis (metade = ,5). Clicar define a nota; "Limpar nota" apaga (apagar a nota do album remove o Log, regra do PRD). Valor numerico com virgula ao lado da nota propria. Avaliar direto na linha da faixa (album) e tambem na tela da Musica. Album e faixas sao avaliados de forma independente; a pagina do album mostra so a nota dada ao album, nunca media das faixas. Avaliar um album da Ouvir depois o remove da lista. Dica de meia estrela ("Clique na metade esquerda ou direita...") na tela da Musica.
- **Teclado nas estrelas.** [ASSUMPTION] Setas esquerda/direita ajustam em passos de 0,5; Home/0 ou "Limpar nota" apaga; role com nome acessivel "Nota 4,5 de 5". Nao mocado.
- **Reordenar arrastando.** Favoritos (albuns e musicas) no Editar perfil e faixas no editor de album do admin: alca de 6 pontos (`cursor: grab`, `touch-action: none`), item arrastado com borda coral e sombra, destino tracejado "Soltar aqui". Sem setas. No mobile, o mock mostra alca de 44x28 abaixo do item. [ASSUMPTION] Suporte a toque e teclado (alca focavel, Espaco pega/solta, setas movem, Esc cancela, anuncio para leitor de tela) a validar; e um pre-requisito para o piso de acessibilidade.
- **Lapis de generos.** Botao de icone ao lado de "Gêneros favoritos": ativa/desativa o modo de edicao (remover chip com ×, adicionar por sugestao). Lapis ativo em coral.
- **Dropdown de busca.** Campo sempre visivel no topo. Digitar abre painel agrupado (Albuns, Musicas, Pessoas). Primeiro resultado destacado; setas cima/baixo movem o destaque, Enter abre o item destacado, Esc fecha, clique fora fecha. Termo casado em coral negrito. Debounce e limite de resultados: [ASSUMPTION] a definir. Campo vazio e focado: nenhum painel. Publico, inclusive para visitante. Mobile: campo em linha propria, painel em largura total.
- **Tabs do perfil.** Clique/toque troca a rota da tab; setas esquerda/direita movem entre abas [ASSUMPTION]; cabecalho nao recarrega.
- **Menu "Avaliacoes".** Abre com clique; itens Albuns e Musicas levam as tabs do proprio perfil.
- **Seguir.** Botao no cabecalho do perfil de outra pessoa e nas linhas das listas de seguidores/seguindo; visitante e levado ao cadastro. Estados em Component Patterns [ASSUMPTION].
- **Menu do avatar.** Abre com clique; Esc/clique fora fecha; contem toggle de tema e Sair (ver Component Patterns).
- **Carregar mais.** Paginacao por botao em Feed e listas do perfil (cursor).
- **Upload de capa (admin).** Escolher arquivo, valida tipo (JPEG/PNG/WebP), tamanho (5 MB) e dimensao (quadrada, 600 px) [ASSUMPTION: limites]; erro exibido na zona da capa.
- **Banidos:** avaliacao em massa, media da comunidade, badges/streaks, exclusao de catalogo (so ocultar).

## Accessibility Floor

O PRD nao define acessibilidade. Baseline proposto, todo item marcado [ASSUMPTION]:

- [ASSUMPTION] Alvo WCAG 2.2 AA para textos e controles principais.
- [ASSUMPTION] Todo controle operavel por teclado, com foco visivel coral (contorno 2px). Ordem de foco segue a ordem de leitura.
- [ASSUMPTION] Estrelas expoem nome e valor (ex.: `aria-label` "Nota 4,5 de 5", "Sem nota. Clique para avaliar"), operaveis por teclado.
- [ASSUMPTION] Painel de busca como combobox/listbox: `aria-label="Resultados da busca"`, `aria-activedescendant`, anuncio de "carregando" ("Buscando resultados") e de "Nada encontrado".
- [ASSUMPTION] Reordenar por arrastar tem alternativa por teclado (ver Interaction Primitives).
- [ASSUMPTION] Alvos de toque >= 44 px no mobile (o mock ja usa alca 44x28 e abas; auditar).
- [ASSUMPTION] `prefers-reduced-motion` desliga skeleton animado e transicoes de arrasto (skeleton do mock ja respeita).
- [ASSUMPTION] Erros de formulario ligados ao campo (`aria-describedby`) e nao dependem so de cor.
- [ASSUMPTION] Botao de olho da senha com `aria-label` "Mostrar senha"/"Ocultar senha" e estado pressionado.
- [ASSUMPTION] Imagens: capas e fotos com texto alternativo (titulo/artista/nome); placeholders anunciados como "Capa (espaço reservado)".
- [ASSUMPTION] Contraste medido: texto pequeno coral no tema claro usa `{colors.coral-text-light}` `#C23516` (4,80:1 sobre Papel); `#FF5A3C` fica em botoes e destaques graficos. Ver DESIGN.md.Colors.
- [ASSUMPTION] Respeitar `prefers-color-scheme` apenas como sugestao inicial? Decisao do usuario: escuro por padrao; toggle manual persistente.

## Key Flows

### Fluxo 1 - Descobrir o album indicado (Joao, dono do projeto, apos a indicacao de um amigo) (jornada do dono)

1. Um amigo indica um album para o Joao.
2. Joao abre o musicboxd (logado).
3. Pesquisa o album no campo da barra do topo; o painel mostra "Albuns", "Musicas" e "Pessoas".
4. Escolhe o album (Enter ou clique); abre a pagina do album.
5. Toca em "+ Adicionar a Ouvir depois" para nao esquecer.
6. Na mesma pagina, rola ate "Amigos que avaliaram".
7. **Climax:** ve, entre as pessoas que segue, quem ja avaliou o album e com quantas estrelas (ex.: Rafael, Mariana, Lucas, Beatriz), e le reviews de outras pessoas abaixo.

Falhas: busca sem resultado -> "Nada encontrado..."; album sem amigos que avaliaram -> [NOTE FOR UX] estado nao mocado, precisa de texto; visitante -> prompt de cadastro ao tentar salvar.

### UJ-1 - Marina avalia e escreve review (PRD)

1. Marina termina um album no trajeto e abre o site.
2. Busca o album pelo campo do topo e abre a pagina.
3. Clica nas estrelas (meia nota possivel): 4,5.
4. Escreve a review em "Sua review" e toca "Publicar".
5. **Climax:** a nota e a review aparecem; o item passa a figurar no perfil dela (tab Albuns, com indicador de review) e no feed dos amigos.

### UJ-2 - Diego avalia o album e as faixas (PRD)

1. Diego da 4 estrelas ao album.
2. Clica estrelas direto nas faixas de destaque na lista de faixas, ou abre a Musica para avaliar.
3. **Climax:** a pagina do album mostra so as 4 estrelas dele, sem media das faixas; suas melhores musicas alimentam as musicas favoritas do perfil (escolhidas no Editar perfil).

### UJ-3 - Tiago segue Marina e salva um album (PRD)

1. Tiago abre o perfil da Marina e toca "Seguir".
2. Abre o Feed e ve as avaliacoes recentes dela.
3. Toca "Ver álbum" no item que gostou.
4. **Climax:** toca "+ Adicionar a Ouvir depois"; o album aparece na tab Ouvir depois do perfil dele.

### UJ-4 - Visitante chega por um link de perfil (PRD)

1. Uma pessoa sem conta abre o link do perfil da Marina.
2. Le bio, generos, favoritos, avaliacoes e Ouvir depois; a barra mostra Entrar / Criar conta.
3. Quer avaliar: toca em uma estrela ou em Seguir e cai no cadastro ("Entre para avaliar esta música.").
4. Cadastro (usuario, email, senha) -> "Verifique seu email" -> ativa a conta pelo link.
5. **Climax:** volta logada e avalia a musica que motivou o cadastro. [NOTE FOR UX] O retorno ao item de origem apos login/verificacao nao esta definido.

### Fluxo Staff - Cadastrar e ocultar album (Staff)

1. Staff acessa `/admin`, Catalogo. 2. "+ Novo álbum" (ou "Editar"). 3. Envia a capa, escolhe generos da lista, adiciona faixas e reordena por arrastar. 4. "Salvar". 5. **Climax:** o album aparece na busca. "Ocultar" o retira da busca sem excluir.

## Responsive & Platform

- **Desktop-first.** Quadro 1280px (conteudo 960px ou 720px centralizado). Mobile 390px.
- **Mobile:** barra do topo vira duas linhas (logo + avatar; busca abaixo em largura total); links do topo somem e viram a barra de abas inferior (Buscar, Ouvir depois, Avaliacoes, Perfil). Painel de busca ocupa a largura da tela. Album em coluna (capa em largura total), estrelas da faixa em segunda linha; amigos em 1 coluna; favoritos em 3 colunas; Ouvir depois em 2 colunas; tabs do perfil rolam na horizontal.
- **Admin:** somente desktop e tema escuro por ora; sem mobile nem claro. [ASSUMPTION] Acesso pelo mobile nao e suportado.
- **Tema:** escuro padrao; toggle para claro. Toggle de tema no menu do avatar (item "Tema claro/escuro") [ASSUMPTION].
- **Sem app nativo** neste escopo.

## Decisoes de produto pendentes / Open items

1. **Senha minima 8 e regra de username** ("letras, numeros, ponto e sublinhado") nao vem do PRD; [ASSUMPTION] a confirmar.
2. **Remover review no admin adiado.** PRD (FR/escopo Staff) previa moderacao pelo Staff; PRD Non-Goals diz "sem moderacao ou fluxo de denuncia", o que conflita internamente. Admin aprovado so com Catalogo e Generos. Assumption anterior "remover apaga so o texto e mantem a nota" fica sem uso ate o retorno da funcao.
3. **Nota atualizada no feed** aparece como item igual a avaliacao nova, sem rotulo "atualizou a nota" [ASSUMPTION], usuario nao decidiu.
4. **Limites de capa 5 MB e minimo 600 px** [ASSUMPTION], nao vem do PRD; tipos JPEG/PNG/WebP e formato quadrado tambem vem do mock. Perfil: "JPG ou PNG" (limites de foto/capa de perfil nao definidos).
5. **Dica de meia estrela** na tela da Musica mantida sem objecao do usuario [ASSUMPTION]; nao ha dica equivalente no album/faixa.
6. **Busca sem pagina de resultados.** Diverge do PRD FR-4 e da arquitetura ("numbered pages for catalog search"). Resultados so no dropdown; nao ha "ver todos os resultados". Implicacoes: limite de resultados por grupo, sem paginacao, sem URL compartilhavel de busca. Confirmar com quem mantem a arquitetura.
7. **Busca por pessoas.** O dropdown mostra "Pessoas", o PRD FR-4 fala so de albuns e musicas; a arquitetura menciona usernames. Confirmar como escopo.
8. **Ordem dos itens na pagina do album** registrada "a confirmar" no memlog.
9. **Reordenar por arrastar:** suporte a toque e teclado a checar (ver Interaction Primitives); pre-requisito de acessibilidade.
10. **Ouvir depois e avaliados viraram tabs do perfil**, com rota propria (`/u/:usuario/ouvir-depois|musicas|albuns`). Isso substitui as telas separadas de listenlist e rated pedidas antes (override do memlog); o menu "Avaliacoes" do topo leva as tabs do proprio perfil.
11. **Admin somente desktop e tema escuro** (sem mobile, sem claro).
12. **Lacunas de tela vs PRD (parte fechada, parte aberta).** Fechadas com padrao razoavel aprovado (marcadas [ASSUMPTION] no corpo): lista de seguidores/seguindo; estados do botao Seguir; sair da conta e toggle de tema (menu do avatar); editar/apagar a propria review (FR-7); estado do botao Ouvir depois (ja avaliado / ja salvo); "nenhum amigo avaliou"; vazios das tabs Musicas e Albuns. Continuam abertas: (e) Feed para visitante (visitante nao segue ninguem; PRD nao define); (h) retorno ao item de origem apos login/verificacao; (i) rotas de auth, edicao de perfil e admin nao definidas (`/seguidores` e `/seguindo` sao [ASSUMPTION]).
12b. **Decisoes registradas pelo usuario.** O feed mantem o texto da review junto da nota (decisao do usuario); o PRD FR-10 descreve so ratings, entao o feed vai alem do PRD nesse ponto. A validacao por revisores (reviewer gate) NAO foi executada, por escolha do usuario; este documento nao passou por revisao independente.
13. **Autenticacao e verificacao de email.** PRD deixou o metodo em aberto; a arquitetura fixou email/senha e o mock inclui "Verifique seu email". Sem recuperar senha (adiado). Envio de email depende de SES (arquitetura, Open Questions).
14. **Tema claro do admin e erro claro:** `--err` claro `#B3260E` existe so no mock de auth. Contraste do coral no claro medido e resolvido com `{colors.coral-text-light}` (DESIGN.md); tema claro do admin segue fora de escopo.

## Mockups de referencia

Ilustracoes 1:1 das telas aprovadas (HTML, tema escuro, claro e mobile). **Estas specs (DESIGN.md e EXPERIENCE.md) vencem qualquer mock em caso de conflito.**

| Tela | Mock |
|---|---|
| Album | [mockups/album.html](mockups/album.html) |
| Feed | [mockups/feed.html](mockups/feed.html) |
| Perfil e edicao de perfil | [mockups/perfil.html](mockups/perfil.html) |
| Musica | [mockups/musica.html](mockups/musica.html) |
| Login, cadastro, verifique e-mail | [mockups/auth.html](mockups/auth.html) |
| Admin (Catalogo e Generos) | [mockups/admin.html](mockups/admin.html) |
| Dropdown de busca | [mockups/busca.html](mockups/busca.html) |

Estados nao mocados (spine-only): lista de seguidores/seguindo, menu do avatar, estados do botao Seguir, editar/apagar review, estados vazios de Musicas e Albuns, "Nenhum amigo avaliou".
