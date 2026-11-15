# Tasks 001 — Captura de áudio

Pré-requisitos: `006-T1` (Vitest), spec 002 até `T-08` (contrato do `/chunk`).

| ID | Tarefa | Depende de | Concluída quando |
|---|---|---|---|
| T-01 | `sessionClock.ts` e `wavEncoder.ts` (extraído sem mudar o formato) | 006-T1 | Teste: cabeçalho WAV válido, duração correta, saída idêntica à função atual para a mesma entrada |
| T-02 | `vadSegmenter.ts` (histerese, pré-roll 300 ms, silêncio 1,2 s, máx. 12 s) | T-01 | Testes com silêncio, fala curta, fala longa (> 12 s) e ruído abaixo do limiar |
| T-03 | `pcm-capture.worklet.ts` + carregamento com fallback de mensagem se não houver suporte | — | Teste do adaptador com worklet falso; verificação manual no Chrome |
| T-04 | `AudioSource` (TAB/MIC): estados, eventos `ended`/`mute`/`devicechange`, liberação de recursos, recuperação do microfone | T-02, T-03 | CA-02, CA-04, CA-05 com `mediaDevices` falso; RNF-03 (nenhuma trilha `live` após `stop`) |
| T-05 | `SegmentUploader` (fila, retry com o mesmo id, limite, contadores, leitura de `status`) | spec 002 T-08 | CA-03; teste de descarte acima do limite |
| T-06 | `WebSpeechMicSource` (adapta o código atual, com `audioSource=MIC`, `source=WEB_SPEECH`, tratamento de `network`/`audio-capture`, sem reinício infinito) | T-04 | CA-07; teste de reinício limitado |
| T-07 | `useCaptureSession` + seletor de modo do microfone + "Parar" com envio dos pendentes | T-04..T-06 | CA-01, CA-06, CA-08 com `renderHook` |
| T-08 | UI: `SourceStatusBadge` por fonte; remover `alert()`; aviso de fone de ouvido; polling de 30 s + sincronização ao reconectar | T-07 | Testes de renderização dos estados; verificação manual |
| T-09 | Remover da página o código substituído (sem perder funções: manual, upload, falante) | T-08 | Página menor; typecheck/lint sem erros; todos os testes verdes |
| T-10 | Roteiro de validação manual numa reunião real (Chrome, com e sem fone) | T-09 | Roteiro executado e resultado registrado em `specs/006-integration-testing/` |
