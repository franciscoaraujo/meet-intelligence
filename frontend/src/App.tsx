/**
 * ============================================================================
 * Meet Intelligence SaaS - Roteamento Principal (App.tsx)
 * ============================================================================
 * Define a árvore de navegação da aplicação Single Page Application (SPA)
 * utilizando React Router v7 e proteção de rotas autenticadas via JWT.
 * Todas as telas seguem o padrão corporativo Google Material Design 3.
 */
import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { AppLayout } from './components/layout/AppLayout';
import { LoginPage } from './pages/LoginPage';
import { LandingPage } from './pages/LandingPage';
import { LandingCustomPage } from './pages/LandingCustomPage';
import { DashboardPage } from './pages/DashboardPage';
import { CalendarPage } from './pages/CalendarPage';
import { MeetingsPage } from './pages/MeetingsPage';
import { LiveMeetingPage } from './pages/LiveMeetingPage';
import { SummariesPage } from './pages/SummariesPage';
import { AiProfilesPage } from './pages/AiProfilesPage';
import { SettingsPage } from './pages/SettingsPage';

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
      </AuthProvider>
    </BrowserRouter>
  );
};

export default App;
