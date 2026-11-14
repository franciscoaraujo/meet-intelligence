# Meet Intelligence — Frontend Web (React 19 + TypeScript + Material Design 3)

Interface web da plataforma **Meet Intelligence**, desenvolvida com **React 19**, **TypeScript**, **Vite** e **Google Material Design 3 (`@material/web`)**, seguindo a identidade visual corporativa do **Google Workspace / Google Meet**.

---

## 🎨 Arquitetura Visual e Componentes (`src/`)

- **`components/layout/AppLayout.tsx`**:
  - Layout estilo Google Workspace com Top App Bar clara (`#f8fafd`), busca global em formato pílula, indicador de IA ativa (Gemini, GPT ou Claude) e Navigation Drawer com seleção em pílula (`#d3e3fd`).
- **`pages/`**:
  - `LoginPage.tsx`: Autenticação corporativa e Google OAuth no padrão Material 3 Sign-In.
  - `DashboardPage.tsx`: Indicadores executivos de uso, horas gravadas, tarefas extraídas e sincronização estrita de reuniões agendadas no Google Meet.
  - `MeetingsPage.tsx`: Gestão de reuniões com filtros rápidos (Filter Chips Material 3), busca instantânea e exclusão em conformidade com a LGPD.
  - `LiveMeetingPage.tsx`: Sala de reunião ao vivo dividida em 3 colunas (Transcrição auditável em tempo real via WebSocket STOMP, Insights Automáticos da IA e Chat RAG com citações clicáveis).
  - `SummariesPage.tsx`: Visualizador de Atas Executivas estilo Google Docs com exportação direta para PDF, DOCX (Word) e TXT.
  - `AiProfilesPage.tsx`: Perfis customizados de prompts de IA por tipo de reunião (Técnica, Comercial, Entrevista, Executiva, Aula).
  - `SettingsPage.tsx`: Painel **BYOK (Bring Your Own Key)** para escolha e configuração de chaves de API da **Google Gemini**, **OpenAI GPT** e **Anthropic Claude**, além de planos SaaS e gestão de membros.

---

## ▶️ Como Executar

```powershell
# Instalar dependências
npm install

# Rodar servidor de desenvolvimento (http://localhost:5173)
npm run dev

# Gerar build de produção
npm run build
```
