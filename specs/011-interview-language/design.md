# Design 011 — Idioma da entrevista

## Componentes
| Componente | Mudança |
|---|---|
| `domain/language/InterviewLanguage` | Novo enum: `PT_BR(pt-BR)`, `EN_US(en-US)`, `FR_FR(fr-FR)`, com nomes em português e no próprio idioma; `fromTag` tolerante (padrão PT) e `parseStrict` (400) |
| `MeetingService.updateLanguage` + `PATCH /meetings/{id}/language` | Grava `meeting.language` |
| `GeminiAudioSttProvider` | Prompt montado com o idioma do `SttRequest` |
| `SegmentEvent` | Novo campo `language` (construtor antigo mantido, padrão PT) |
| `UtteranceClassifier` | Regras por idioma (`Rules`); `classify(text, speaker, role, language)`. A versão sem idioma usa português |
| `QuestionDetectionService` | Guarda o idioma da reunião (como já faz com o papel) e o repassa ao classificador |
| `CopilotPromptBuilder` | Bloco `<answer_language>` quando o idioma não é português |
| `CopilotAnswerValidator` | Padrões de experiência e de pergunta técnica em inglês e francês |
| Frontend: `features/live/interviewLanguage.ts` | Opções, preferência no navegador |
| `LiveMeetingPage` | Seletor "Idioma"; repassa o idioma à captura (Web Speech) |
| `quickCapture.ts` | `language` da preferência |
| `WebSpeechMicSource` / `useCaptureSession` | Opção `language` |

## Decisões
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Usar o campo `meeting.language` existente | Coluna nova | Já existe, já chega aos provedores de STT; nenhuma migration |
| Regras do classificador por idioma | Aplicar todas as regras sempre | "comment" (francês: como) é palavra comum em inglês; regras misturadas gerariam falsos positivos |
| Instrução de idioma no prompt do usuário | Um arquivo de prompt por idioma | Um só prompt versionado; a instrução é curta e explícita |
| Interface e insights continuam em português | Traduzir tudo | Fora do pedido; o usuário lê a interface em português |
