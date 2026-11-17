# Spec 019 — Adesão ao copiloto de entrevistas nas landing pages

Prioridade: **P2** · Status: **Implementada (2026-11-16; falta a revisão visual do usuário, CA-05)** · Origem: pedido do usuário (2026-11-16): "modificar as landing pages para incluir a forma de adesão ao aplicativo"

## Problema
As duas landing pages (`/landing` e `/landing_2`) só oferecem um botão que leva ao login. O visitante não sabe **como contratar**: se há teste grátis, que planos existem nem o que cada plano inclui.

O modelo comercial também mudou para as entrevistas: a IA vai **incluída no preço**, com o Deepgram na transcrição (spec 018). As páginas falam só de chave própria e de IA local.

## Decisões do usuário (2026-11-16)
- **Forma de adesão:** teste grátis, com planos.
  - O botão do teste grátis leva ao cadastro.
  - O pagamento fica para uma spec própria. Até lá, os planos pagos levam a "Fale conosco".
- **Preços:** **sem valor publicado** por enquanto; aparece "Sob consulta".
- **Escopo do modelo novo:** só o **copiloto de entrevistas** usa a IA incluída. Reuniões e os outros usos continuam com o discurso atual de **IA local**, sem mudança.

## Requisitos funcionais
- **RF-01 Seção "Planos do copiloto de entrevistas":** nova seção `id="planos-entrevista"`, igual nas duas páginas, logo antes do rodapé. Ela traz:
  - um título e uma frase deixando claro que a seção vale para o copiloto de entrevistas, com transcrição e respostas por IA incluídas, sem precisar de chave de API;
  - os passos da adesão (RF-03);
  - os cartões dos planos (RF-02);
  - uma nota de que reuniões e outros usos seguem os modelos de IA local já apresentados na página.
- **RF-02 Planos:** três cartões, definidos num único arquivo de configuração (`features/landing/interviewPlans.ts`) para que nomes, horas e textos mudem num lugar só:

  | Plano | Inclui | Preço | Botão |
  |---|---|---|---|
  | **Teste grátis** | conhecer o copiloto numa entrevista de teste; sem cartão de crédito | "Grátis" | "Começar teste grátis" → cadastro |
  | **Profissional** (destacado) | 10 h de entrevista por mês, transcrição e respostas em tempo real, base de conhecimento com currículo e vaga, PT/EN/FR | "Sob consulta" | "Fale conosco" |
  | **Intensivo** | 30 h de entrevista por mês, tudo do Profissional | "Sob consulta" | "Fale conosco" |

  - As horas são uma proposta inicial, ajustável no arquivo de configuração.
  - Nenhum texto promete algo que o sistema não faz. Em particular, não promete limite nem duração do teste, que ainda não são controlados.
- **RF-03 Como aderir:** três passos curtos:
  1. criar a conta com o Google;
  2. testar o copiloto numa entrevista;
  3. escolher um plano e falar com a gente para ativar.
- **RF-04 Destino dos botões:**
  - **"Começar teste grátis":** visitante vai para `/login`, onde o cadastro é o primeiro acesso com Google; usuário já logado vai para o painel (`/`).
  - **"Fale conosco":** com `VITE_CONTACT_EMAIL` configurada, abre um e-mail para esse endereço com assunto `Plano <nome> - copiloto de entrevistas`. Sem a variável, leva ao cadastro (`/login`), para não publicar um contato pessoal fixo no código.
- **RF-05 Navegação:** o menu do topo das duas páginas ganha o link **"Planos"**, que leva à seção.
- **RF-06 Conteúdo existente:** o restante das páginas não muda: IA local, BYOK, simulador e comparativos, voltados a reuniões e outros usos.

## Regras de negócio
- **RN-01** Nenhum preço em reais aparece nos planos enquanto o usuário não definir os valores.
- **RN-02** Sem pagamento online nesta spec. Nenhum botão leva a checkout.

## Fora do escopo
- Pagamento (Stripe/Pix), assinatura, cotas de horas e limite do teste grátis no backend.
- Reescrever as seções existentes das landings.
- Formulário de contato com backend.

## Critérios de aceite
- **CA-01** As duas páginas mostram a seção com os três planos, "Grátis" e duas vezes "Sob consulta", e o menu tem o link "Planos" apontando para `#planos-entrevista`.
- **CA-02** "Começar teste grátis" leva a `/login` para visitante e a `/` para usuário logado.
- **CA-03** "Fale conosco" gera `mailto:` com o endereço de `VITE_CONTACT_EMAIL` e o assunto do plano. Sem a variável, leva a `/login`.
- **CA-04** Nenhum valor em `R$` dentro da seção de planos.
- **CA-05** Revisão visual nas duas páginas, desktop e celular, feita pelo usuário.

## Implementação
- Planos e passos: `frontend/src/features/landing/interviewPlans.ts`.
- Seção: `frontend/src/components/landing/InterviewPlansSection.tsx`, usada antes do rodapé em `LandingPage.tsx` e `LandingCustomPage.tsx`, com o link "Planos" no menu das duas.
- E-mail de contato: `VITE_CONTACT_EMAIL` em `frontend/.env.local`, documentada em `frontend/.env.example`.

## Testes
- `interviewPlans.test.tsx` (5): planos e preços sem `R$` (CA-01, CA-04); teste grátis para visitante e para usuário logado (CA-02); "Fale conosco" com e sem e-mail (CA-03).
- Frontend 125, 0 falhas; checagem de tipos, build e tamanho do bundle ok.
- **Prints** em 2026-11-16, com o Chrome sem interface:
  - `/landing` em 1400 px e `/landing_2` em 520 px: a seção aparece completa, com os três cartões em coluna no celular;
  - em 390 px, a página inteira (inclusive as seções antigas) sai cortada à direita, porque o Chrome sem interface tem largura mínima de janela;
  - falta conferir num celular de verdade (CA-05).

