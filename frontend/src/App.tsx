/**
 * ============================================================================
 * Meet Intelligence SaaS - Roteamento Principal (App.tsx)
 * ============================================================================
 * Define a árvore de navegação da aplicação Single Page Application (SPA)
 * utilizando React Router v7 e proteção de rotas autenticadas via JWT.
 * Todas as telas seguem o padrão corporativo Google Material Design 3.
 */
import React, { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { AppLayout } from './components/layout/AppLayout';

/**
 * Páginas carregadas sob demanda (spec 008, RF-19): o login não baixa o código da sala ao vivo,
 * e cada tela vira um arquivo separado no build.
 */
const lazyPage = <K extends string>(load: () => Promise<Record<K, React.ComponentType>>, name: K) =>
  lazy(() => load().then((module) => ({ default: module[name] })));

const LoginPage = lazyPage(() => import('./pages/LoginPage'), 'LoginPage');
const LandingPage = lazyPage(() => import('./pages/LandingPage'), 'LandingPage');
const LandingCustomPage = lazyPage(() => import('./pages/LandingCustomPage'), 'LandingCustomPage');
const DashboardPage = lazyPage(() => import('./pages/DashboardPage'), 'DashboardPage');
const CalendarPage = lazyPage(() => import('./pages/CalendarPage'), 'CalendarPage');
const MeetingsPage = lazyPage(() => import('./pages/MeetingsPage'), 'MeetingsPage');
const LiveMeetingPage = lazyPage(() => import('./pages/LiveMeetingPage'), 'LiveMeetingPage');
const SummariesPage = lazyPage(() => import('./pages/SummariesPage'), 'SummariesPage');
const AiProfilesPage = lazyPage(() => import('./pages/AiProfilesPage'), 'AiProfilesPage');
const KnowledgeBasePage = lazyPage(() => import('./pages/KnowledgeBasePage'), 'KnowledgeBasePage');
const SettingsPage = lazyPage(() => import('./pages/SettingsPage'), 'SettingsPage');

const PageFallback: React.FC = () => (
  <div className="flex items-center justify-center h-[60vh]" role="status" aria-label="Carregando">
    <div className="w-6 h-6 rounded-full border-2 border-[#d3e3fd] border-t-[#0b57d0] motion-safe:animate-spin" />
  </div>
);

/**
 * Guardião de rotas autenticadas: redireciona usuários sem sessão ativa para /login.
 */
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
};

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Suspense fallback={<PageFallback />}>
        <Routes>
          {/* Rotas públicas (Landing Pages comerciais + Autenticação) */}
          <Route path="/landing" element={<LandingPage />} />
          <Route path="/landing_2" element={<LandingCustomPage />} />
          <Route path="/login" element={<LoginPage />} />

          {/* Rotas autenticadas dentro do AppLayout estilo Google Workspace */}
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <AppLayout />
              </ProtectedRoute>
            }
          >
            <Route index element={<DashboardPage />} />
            <Route path="calendar" element={<CalendarPage />} />
            <Route path="meetings" element={<MeetingsPage />} />
            <Route path="summaries" element={<SummariesPage />} />
            <Route path="ai-profiles" element={<AiProfilesPage />} />
            <Route path="knowledge" element={<KnowledgeBasePage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>

          {/* Sala de Reunião ao Vivo (Layout de 3 colunas sincronizado via WebSocket STOMP) */}
          <Route
            path="/meetings/:id/live"
            element={
              <ProtectedRoute>
                <LiveMeetingPage />
              </ProtectedRoute>
            }
          />

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        </Suspense>
      </AuthProvider>
    </BrowserRouter>
  );
};

export default App;
