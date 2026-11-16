# Design 010 — Captura avulsa

## Componentes
| Componente | Mudança |
|---|---|
| `features/meetings/quickCapture.ts` | Novo. `buildQuickCaptureRequest(now)` (corpo do POST) e `isQuickCapture(meeting)` |
| `components/meetings/QuickCaptureButton.tsx` | Novo. Cria a sessão e navega; estados "Abrindo…" e erro; variantes `sidebar` e `primary` |
| `components/layout/AppLayout.tsx` | Botão no topo do menu lateral; item renomeado |
| `pages/DashboardPage.tsx` | Botão no cabeçalho; "Captura avulsa" na lista |
| `pages/MeetingsPage.tsx` | Chip "Captura avulsa" nos cards; texto do estado vazio |
| `components/modals/NewMeetingModal.tsx` | Link opcional; validado só se informado; enviado como `undefined` quando vazio |
| `MeetingService.createMeeting` | Link em branco → `null` |

## Decisões
| Decisão | Alternativas | Justificativa |
|---|---|---|
| Reaproveitar `POST /meetings` | Endpoint novo `/captures` | O backend já aceita reunião sem link; nenhuma API nova |
| `meetingType = GENERAL` | `INTERVIEW` | O copiloto de entrevista depende do papel do usuário (candidato/entrevistador), não do tipo; GENERAL não restringe o uso |
| Título com data e hora | Pedir o título antes | Um clique; o título serve para achar a sessão depois |
