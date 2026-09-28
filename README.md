# Musicboxd

## Ideia principal

Musicboxd é um tracker musical e rede social para música, no espírito do Letterboxd. As pessoas registram os álbuns e músicas que ouviram, dão notas, escrevem reviews e seguem amigos para acompanhar o que estão ouvindo.

É feito para quem gosta de música e quer um lugar para expressar seu gosto, compartilhá-lo e acompanhar os amigos — um projeto pessoal, construído para ser usado no dia a dia por seu criador e por quem quiser participar.

## Principais funcionalidades

- **Catálogo e busca** — Álbuns e Músicas mantidos pela equipe (Staff), com busca por título, artista e gênero.
- **Avaliar e logar** — Dar nota (0 a 5, em passos de meia estrela) a um Álbum loga automaticamente esse Álbum para o usuário; Músicas podem ser avaliadas de forma independente.
- **Reviews** — Texto livre sobre um Álbum, um por usuário.
- **Listenlist** — Lista de Álbuns que o usuário quer ouvir depois.
- **Perfil e favoritos** — Página pública com identidade, gêneros favoritos e até 5 Álbuns + 5 Músicas favoritas.
- **Seguir e feed** — Seguir outros usuários e acompanhar suas avaliações e reviews recentes em um feed.
- **Acesso de visitante** — Quem não tem conta pode navegar e ler perfis, reviews e avaliações; só precisa de conta para interagir.

## Arquitetura base

- **Monólito modular** em Spring Boot (Java), dividido em módulos de domínio (`accounts`, `catalog`, `ratings`, `reviews`, `listenlist`, `social`, `profiles`, `uploads`, `feed`), cada um com seu próprio schema no Postgres.
- **Contrato único de API** sob `/api/v1`, documentado via OpenAPI (springdoc); o cliente TypeScript do frontend é gerado automaticamente a partir desse contrato.
- **Frontend em React (Vite)**, uma SPA que consome exclusivamente essa API.
- **Infraestrutura simples**: um único host EC2 rodando `api`, `postgres` e `nginx` via Docker Compose, com TLS, backups do banco e alarmes de custo/abuso — dimensionado para um projeto pessoal, não para escala.

## Estrutura do repositório

```
musicboxd/
  api/      # aplicação Spring Boot
  web/      # SPA em React (Vite)
  deploy/   # docker-compose, configuração do nginx
```

Documentação de planejamento (PRD, arquitetura, specs, tickets) vive em `_bmad-output/`.
