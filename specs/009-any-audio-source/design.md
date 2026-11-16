# Design 009 — Captura de áudio de qualquer fonte

## Componentes
| Componente | Mudança |
|---|---|
| `features/capture/audioSource.ts` | Opções novas no `getDisplayMedia`; lê a superfície da trilha de vídeo; callback opcional `onSurface`; ajuda de "Sem áudio" por superfície (`noAudioHint`) |
| `features/capture/types.ts` | Tipo `CaptureSurface = 'browser' \| 'monitor' \| 'window' \| 'unknown'` |
| `features/capture/useCaptureSession.ts` | `SourceView.surface`; repassa `onSurface` |
| `components/capture/SourceStatusBadge.tsx` | "Áudio do computador (aba \| tela inteira \| janela)" |
| `features/transcription/speaker.ts` | Origem `TAB` = "Áudio do computador" |
| `pages/LiveMeetingPage.tsx` | Botão e dica, aviso de ruído para tela inteira, aviso de consentimento |
| `SpeakerAttributionService` | `REMOTE_LABEL = "Participante remoto"` |
| `test/fakes/media.ts` | Superfície configurável e `getSettings()` nas trilhas falsas |

## Decisões
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Manter `TAB` como nome técnico | Renomear para `SYSTEM`/`COMPUTER` | Não muda API, banco nem dados antigos; só os textos mudam |
| Superfície pela trilha de vídeo | Perguntar ao usuário antes do seletor | O navegador já informa; nenhum passo a mais |
| Manter a trilha de vídeo viva | Parar o vídeo logo após obter o áudio | O fim da trilha de vídeo é o sinal de "Parar compartilhamento" (spec 001); o custo é baixo |
| Aviso de ruído só para tela inteira | Sempre | Numa aba, o som é só daquele site |
