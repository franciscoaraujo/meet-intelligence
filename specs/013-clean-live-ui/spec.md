# Spec 013 — Interface limpa da sala ao vivo

Prioridade: **P3** · Status: **Implementada (2026-11-16; testes automatizados, revisão visual pendente)** · Origem: pedido do usuário ("melhore a interface para ficar mais clean e profissional") depois de usar a sala ao vivo

## Problemas observados (print de 15/11)
- O cabeçalho tem 9 controles, e vários quebram em duas linhas ("Sair do modo entrevista", "Parar captura do computador").
- "Pergunta terminou" aparece duas vezes no modo entrevista.
- O seletor do microfone ocupa espaço fixo, mesmo travado no modo entrevista.
- As bandeiras dos idiomas viram letras no Windows ("us English").
- O aviso de consentimento ocupa uma faixa inteira; o papel (Candidato/Entrevistador) ocupa outra.
- Emojis misturados com ícones (🎯, ❓, 💡) e fundos azulados em todos os cards.

## Requisitos
- **RF-01** O cabeçalho cabe em uma linha a partir de 1366 px, sem textos quebrados.
  - **Esquerda:** voltar e título; abaixo, idioma, papel (Candidato/Entrevistador), estado da conexão e consentimento (LGPD).
  - **Direita:** áudio do computador, "Pergunta terminou" (só fora do modo entrevista), modo entrevista, menu "⋯" e o botão de captura com o cronômetro.
- **RF-02** O menu "⋯" reúne as ações secundárias: Upload de mídia, Consolidar com IA e o modo do microfone. O modo do microfone fica desabilitado no modo entrevista, com a explicação.
- **RF-03** O botão de captura mostra "Iniciar captura" ou, gravando, o cronômetro e "Encerrar".
- **RF-04** O consentimento continua visível, mas como um item curto ("Consentimento obrigatório (LGPD)") com o texto completo na dica.
- **RF-05** Os nomes dos idiomas aparecem sem emoji de bandeira, com sigla (PT, EN, FR).
- **RF-06** Os cards do copiloto e as falas ganham fundo branco, borda neutra e ícones no lugar de emojis. "Responder com IA" na fala vira um ícone que aparece ao passar o mouse.
- **RF-07** Nenhuma funcionalidade é removida; só muda de lugar.

## Critérios de aceite
- **CA-01** O cabeçalho mostra os controles principais; Upload, Consolidar e o modo do microfone estão no menu "⋯".
- **CA-02** No modo entrevista, "Pergunta terminou" não aparece no cabeçalho, só no painel, e o modo do microfone no menu está desabilitado.
- **CA-03** Gravando, o botão mostra o cronômetro e "Encerrar"; clicar encerra.
- **CA-04** Os testes existentes continuam verdes.

## Implementação
- `components/live/LiveMeetingHeader.tsx`: cabeçalho novo, com menu "⋯" (Upload, Consolidar, modo do microfone) e botão de captura com cronômetro.
- Saem da página: a faixa de consentimento (vira item curto com dica) e a linha "Seu papel nesta entrevista" (vira seletor no cabeçalho).
- Ícones no lugar de 🎯, ❓ e 💡; cards e falas com fundo branco e borda neutra; idiomas com sigla (PT, EN, FR).
- Testes: `LiveMeetingHeader.test.tsx` (4). Frontend 105.

## Adição (2026-11-16): diálogo de confirmação do app
- `components/common/ConfirmDialog.tsx` substitui `window.confirm` na exclusão de reuniões (`MeetingsPage`) e na remoção de documentos (`KnowledgeBasePage`).
- Mostra o nome do item, foca em "Cancelar" nas ações destrutivas, fecha com Esc ou clique fora, exibe "Excluindo…" e mostra o erro dentro do diálogo (sem `alert`).
- Testes: `ConfirmDialog.test.tsx` (4). Frontend 112.
- Pendente: os `alert()` de erro em `SettingsPage` (plano, convite, configuração de IA).

