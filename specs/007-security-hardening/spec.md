# Spec 007 — Segurança: login, WebSocket, isolamento entre organizações e chaves criptografadas

Prioridade: **P1 (bloqueia a abertura para clientes)** · Status: **Implementada (2026-11-16; testes automatizados e servidor local conferidos; falta o CA-12 no navegador)** · Origem: diagnóstico inicial (S-01..S-03) e auditoria de 2026-11-16, pedida pelo usuário

## Problema
O copiloto lida com currículos, conversas de entrevista e chaves de IA, e o produto vai virar SaaS. A auditoria do código em 2026-11-16 encontrou falhas que permitem a uma pessoa **entrar na conta de outra**, **ler e falsificar entrevistas alheias** e **ler as chaves de IA** de quem tiver acesso ao banco:

| # | Falha | Onde | Impacto |
|---|---|---|---|
| S-04a | `POST /auth/google` com um **e-mail qualquer** como credencial faz login como esse e-mail ("login de demonstração") | `AuthService` | qualquer pessoa entra em qualquer conta |
| S-04b | Se a validação do ID Token no Google falha, o token é **decodificado sem conferir a assinatura** | `AuthService` | token forjado com qualquer e-mail é aceito |
| S-04c | Não confere se o token do Google foi emitido para o **nosso** Client ID (`aud`/`azp`) | `AuthService` | token de outro app do Google é aceito |
| S-04d | `POST /auth/google/config` é público e troca o Client ID do login do sistema inteiro | `AuthController` | qualquer pessoa derruba ou desvia o login |
| S-04e | O segredo que assina os tokens tem **valor padrão no `application.yml`**, que está num repositório público | `application.yml` (`app.jwt.secret`) | servidor sem `JWT_SECRET` aceita tokens forjados para qualquer usuário |
| S-01 | WebSocket `/ws` sem autenticação e aceitando qualquer origem | `SecurityConfig`, `WebSocketConfig` | quem souber o id da reunião assina a transcrição e as respostas |
| S-01b | O broker em memória aceita `SEND` de clientes para `/topic/**` | `WebSocketConfig` | qualquer um publica transcrição ou resposta falsa no card de outra pessoa |
| S-02 | Endpoints de reunião sem conferir a organização: buscar, alterar status e excluir a reunião, chat, exportação da ata, insights, gravações, lista e correção de transcrição; excluir perfil de IA | controllers e serviços | ler, alterar ou apagar dados de outra organização |
| S-03 | Chaves de IA das organizações em texto puro no banco | `OrganizationAiConfig` | vazamento do banco expõe as chaves |
| S-05 | Qualquer membro muda o plano da organização e convida membros com qualquer papel, inclusive `OWNER` | `OrganizationController` | escalada de privilégio |
| S-06 | CORS do HTTP aceita qualquer origem com credenciais | `SecurityConfig` | qualquer site faz chamadas em nome do usuário |

## Objetivo
Só quem prova a identidade pelo Google entra. Cada usuário só vê e altera dados da própria organização, inclusive no tempo real. As chaves de IA ficam criptografadas no banco. Nada disso depende de um segredo escrito no código.

## Requisitos funcionais

### Login (S-04)
- **RF-01 Sem login por e-mail:** uma credencial que é só um e-mail é recusada (401). Exceção: o **login de demonstração** do RF-02.
- **RF-02 Demonstração controlada:**
  - nova configuração `app.auth.demo-login-enabled` (`AUTH_DEMO_LOGIN_ENABLED`), padrão **`false`**;
  - ligada, ela aceita **apenas** o e-mail fixo de demonstração (`app.auth.demo-email`, padrão `demo.user@meetingintelligence.ai`);
  - `GET /auth/google/config` informa `demoLoginEnabled`, e a tela de login só mostra o botão de demonstração quando ele estiver ligado.
- **RF-03 ID Token só pelo Google:**
  - o ID Token é validado no `tokeninfo` do Google; se o Google recusa ou não responde, o login falha (401). Não há decodificação local sem assinatura;
  - o `aud` precisa ser o Client ID configurado;
  - o `email_verified` precisa ser verdadeiro.
- **RF-04 Access Token do Google:**
  - o `ya29.*` é conferido no `tokeninfo?access_token=`, e o `aud` ou `azp` precisa ser o Client ID configurado;
  - só depois o `userinfo` é lido para nome e foto.
- **RF-05 Configuração do Client ID:** `POST /auth/google/config` exige login com papel `OWNER` ou `ADMIN`. O `GET` continua público, porque a tela de login precisa dele.
- **RF-06 Segredo do token:**
  - `app.jwt.secret` não tem mais valor padrão no código;
  - sem `JWT_SECRET` (ou com menos de 32 bytes), o backend gera um segredo aleatório ao subir e avisa no log. Os tokens deixam de valer a cada reinício; é seguro, mas incômodo.
  - Em produção, `JWT_SECRET` é obrigatório na prática. Para desenvolvimento, fica no `backend/.env` (spec 018).

### Tempo real (S-01)
- **RF-07 Autenticação no CONNECT:**
  - o cliente STOMP envia `Authorization: Bearer <token>` no frame `CONNECT`;
  - sem token válido, a conexão é recusada (`ERROR`).
- **RF-08 Assinatura só da própria organização:**
  - `SUBSCRIBE` em `/topic/meetings/{id}/**` só é aceito se a reunião existe e é da organização do token;
  - qualquer outro destino é recusado.
- **RF-09 Clientes não publicam:** todo `SEND` vindo do cliente é recusado. Os eventos saem só do servidor.
- **RF-10 Origens:** o endpoint `/ws` aceita só as origens do RF-15.

### Isolamento entre organizações (S-02)
- **RF-11 Guarda central de reunião:**
  - toda rota `/api/v1/meetings/{meetingId}` e `/api/v1/meetings/{meetingId}/**` confere, antes do controller, se a reunião é da organização do usuário;
  - se não for, ou se ela não existir, a resposta é **404**: não revela que a reunião existe;
  - vale para os endpoints atuais e para os que vierem.
- **RF-12 Ids dentro da reunião:** trecho de transcrição (`segmentId`) e gravação (`recordingId`) precisam pertencer à reunião da URL; senão, 404. Perguntas já fazem isso.
- **RF-13 Perfis de IA:** excluir um perfil confere a organização (404 se for de outra).

### Chaves de IA criptografadas (S-03)
- **RF-14 Criptografia em repouso:**
  - as colunas `gemini_api_key`, `openai_api_key`, `grok_api_key` e `claude_api_key` passam a guardar o valor cifrado com AES-256-GCM, no formato `enc:v1:<base64(iv + texto cifrado)>`;
  - a chave de criptografia vem de `app.security.encryption-key` (`APP_ENCRYPTION_KEY`, 32 bytes em base64);
  - sem ela, o backend **não sobe** e explica o motivo;
  - valores antigos em texto puro continuam legíveis e são cifrados automaticamente na primeira inicialização com a chave;
  - a tela continua mostrando só a chave mascarada.

### CORS e papéis (S-05, S-06)
- **RF-15 Origens permitidas:**
  - `app.security.allowed-origins` (`APP_ALLOWED_ORIGINS`, lista separada por vírgula), padrão `http://localhost:5173`;
  - vale para o CORS do HTTP e para o `/ws`.
- **RF-16 Papéis:**
  - mudar o plano e convidar membros exigem `OWNER` ou `ADMIN`;
  - convidar alguém como `OWNER` exige ser `OWNER`;
  - fora disso, 403.

## Regras de negócio
- **RN-01** Nenhum segredo (chave de API, segredo de token, chave de criptografia) tem valor padrão real no código ou no Git.
- **RN-02** Recurso de outra organização responde 404, nunca 403, para não confirmar que existe.
- **RN-03** Falha de segurança fecha, nunca abre: na dúvida (Google fora do ar, token sem organização), recusa.

## Fora do escopo (próxima spec de segurança)
- Limite de requisições por usuário e organização, para proteger o custo da IA incluída.
- LGPD: prazo de guarda, exclusão e exportação de dados; política de privacidade e termos.
- Cabeçalhos de segurança HTTP, HTTPS obrigatório e registro de auditoria.
- Rotação da chave de criptografia (o prefixo `v1` já prepara isso).

## Critérios de aceite
- **CA-01** Login com `credential = "alguem@empresa.com"` → 401. Com a demonstração ligada, só o e-mail de demonstração entra.
- **CA-02** ID Token recusado pelo Google, com `aud` de outro app ou com `email_verified=false` → 401; nenhum caminho decodifica o token localmente.
- **CA-03** Access Token com `aud`/`azp` de outro app → 401.
- **CA-04** `POST /auth/google/config` sem login → 401/403; com `MEMBER` → 403; com `OWNER` → 200.
- **CA-05** Sem `JWT_SECRET`, dois inícios geram segredos diferentes, e um token do primeiro é inválido no segundo. Com `JWT_SECRET`, o token continua válido.
- **CA-06** WebSocket:
  - `CONNECT` sem token → recusado;
  - `SUBSCRIBE` numa reunião de outra organização → recusado;
  - `SUBSCRIBE` na própria → aceito;
  - qualquer `SEND` → recusado.
- **CA-07** Usuário da organização A recebe 404 em todos os endpoints `/meetings/{id}/**` de uma reunião da organização B, inclusive GET, PATCH e DELETE da reunião, chat, exportação, insights, gravações e transcrições.
- **CA-08** Corrigir um trecho ou pedir a URL de uma gravação de outra reunião pela URL da própria → 404.
- **CA-09** A chave salva aparece no banco como `enc:v1:...`, é lida de volta igual e a tela mostra só a versão mascarada. Um valor antigo em texto puro é cifrado na inicialização. Sem `APP_ENCRYPTION_KEY`, o backend não sobe.
- **CA-10** `MEMBER` mudando o plano ou convidando → 403; `ADMIN` convidando `OWNER` → 403.
- **CA-11** CORS: uma origem fora da lista não recebe `Access-Control-Allow-Origin`.
- **CA-12** No navegador, com a configuração local, o login com Google, a sala ao vivo (transcrição, perguntas e respostas em tempo real) e as configurações de IA continuam funcionando.

## Achados extras durante a implementação
- **Organização vinda da URL:** `GET /organization`, `PATCH /organization/plan`, `POST /organization/members/invite` e `GET /dashboard/metrics` usavam o `organizationId` da URL ou, sem ele, **a primeira organização do banco**. Qualquer usuário via e alterava outra organização. Agora usam sempre a organização do token. O frontend não mandava esse parâmetro, então nada muda para ele.
- **Erro 500 com detalhe interno:** o tratador genérico devolvia a mensagem da exceção (SQL, caminhos) ao cliente e transformava "acesso negado" em 500. Agora o detalhe fica só no log, `AccessDeniedException` → 403 e login recusado → 401.
- **Segredo de criptografia sem uso:** `app.encryption.secret-key` (`ENCRYPTION_SECRET_KEY`) tinha valor padrão no `application.yml` e não era usado em lugar nenhum. Foi removido. O `.env.example` trazia valores prontos para `JWT_SECRET` e `ENCRYPTION_SECRET_KEY`; agora traz instruções e campos vazios.
- **Token do Google do usuário:** `users.google_refresh_token`, que guarda o Access Token do Google usado na agenda, também passou a ser cifrado (RF-14).
- **E-mail de demonstração parecido com um token:** `demo.user@meetingintelligence.ai` tem três partes separadas por ponto e seria tratado como ID Token. A demonstração é verificada antes, e um ID Token nunca contém `@`. O erro foi pego pelo teste antes de ir para o código final.
- **Tela de login:** a função de login de demonstração existe no frontend, mas nenhum botão a usa. Nada foi mudado na tela; o `GET /auth/google/config` já informa `demoLoginEnabled` para quando o botão voltar.

## Implementação
- **Login:** `GoogleTokenVerifier` (tokeninfo + userinfo, `aud`/`azp`, `email_verified`); `AuthService` sem decodificação local nem login por e-mail; `AuthenticationFailedException` → 401.
- **Segredo do token:** `JwtService.secretBytes` (aleatório quando ausente ou curto); `JwtService.toPrincipal`, usado pelo filtro HTTP e pelo WebSocket.
- **WebSocket:** `StompSecurityInterceptor` (CONNECT, SUBSCRIBE e SEND); `WebSocketConfig` com origens e interceptor; `websocket.ts` envia o token no `beforeConnect`.
- **Isolamento:** `MeetingAccessGuard` (interceptor MVC em `/api/v1/meetings/*` e `/**`); `MeetingRepository.existsByIdAndOrganization_Id`; trecho, gravação e perfil de IA conferidos.
- **Criptografia:**
  - `SecretProtector` e `EncryptedStringConverter` no domínio, para o domínio não depender da infraestrutura;
  - `SecretCipher` (AES-256-GCM) e `SecretEncryptionMigrator` na infraestrutura;
  - migration `V13__encrypted_secrets.sql` (colunas de chave → `TEXT`).
- **CORS e papéis:** `AllowedOrigins`; `@PreAuthorize` em plano, convite e configuração do Client ID.
- **Configuração:** `APP_ENCRYPTION_KEY`, `JWT_SECRET`, `APP_ALLOWED_ORIGINS`, `AUTH_DEMO_LOGIN_ENABLED`, documentadas no `.env.example`.

## Testes
- `SecurityHardeningIT` (12), com servidor real em porta aleatória e PostgreSQL real:
  - 404 em 14 endpoints da reunião de outra organização (CA-07);
  - trecho de outra reunião (CA-08);
  - organização do token (S-05);
  - papéis (CA-10);
  - login por e-mail e demonstração desligada (CA-01);
  - configuração do Client ID (CA-04);
  - token forjado com outro segredo;
  - CORS (CA-11);
  - WebSocket sem token, assinatura de outra organização e `SEND` (CA-06).
- `GoogleTokenVerifierTest` (8): token válido, `aud` de outro app, e-mail não verificado, token forjado recusado pelo Google, Google fora do ar, servidor sem Client ID, Access Token com `azp` certo e errado (CA-02, CA-03).
- `AuthAndSecretsTest` (12): login (CA-01, RF-02), segredo do token (CA-05) e criptografia (CA-09).
- `SecretsAtRestIT` (3, PostgreSQL real): valor cifrado no banco e lido de volta, migração de valor antigo uma vez só, colunas `TEXT` (CA-09).
- `TranscriptionServiceTest` (+1): trecho de outra reunião. `TranscriptionPipelineE2EIT` passou a conectar no WebSocket com token.
- Frontend: `websocket.test.ts` (2), o token no `CONNECT`.
- Backend 290, frontend 127, 0 falhas.
- **Verificação de mutação:** desligando a guarda de reunião, o bloqueio de `SEND` e a recusa do login por e-mail, 6 testes falham.
- **Servidor local em 2026-11-16, com `JWT_SECRET` e `APP_ENCRYPTION_KEY` no `backend/.env`:**
  - V13 aplicada;
  - 4 segredos em texto puro cifrados na inicialização, nenhum restante;
  - login com e-mail → 401; `POST /auth/google/config` sem login → 403;
  - `/ws/info` com origem `localhost:5173` → 200, com outra origem → 403.
- **Falta o CA-12:** login com Google, sala ao vivo e configurações de IA no navegador.

