import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Video,
  ShieldCheck,
  CalendarCheck,
  Lock,
  Settings,
  ExternalLink,
  AlertCircle,
  CheckCircle2,
  KeyRound,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { api } from '../services/api';

declare global {
  interface Window {
    google?: any;
  }
}

export const LoginPage: React.FC = () => {
  const { loginWithGoogle, loginDemo } = useAuth();
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const DEFAULT_GOOGLE_CLIENT_ID =
    '726281906025-91lbpv9q3v65qqv2uf1117d10smgnm1u.apps.googleusercontent.com';
  const [googleClientId, setGoogleClientId] = useState<string>(() => {
    return (
      import.meta.env.VITE_GOOGLE_CLIENT_ID ||
      localStorage.getItem('google_client_id') ||
      DEFAULT_GOOGLE_CLIENT_ID
    );
  });
  const [clientIdInput, setClientIdInput] = useState<string>(googleClientId);
  const [directTokenInput, setDirectTokenInput] = useState<string>('');
  const [showConfigPanel, setShowConfigPanel] = useState<boolean>(false);
  const [configSavedMsg, setConfigSavedMsg] = useState<string | null>(null);

  const googleBtnContainerRef = useRef<HTMLDivElement | null>(null);
  const navigate = useNavigate();

  // Carrega o Google Client ID salvo no Backend se existir
  useEffect(() => {
    api
      .get('/auth/google/config')
      .then((res) => {
        if (res.data?.configured && res.data?.clientId) {
          setGoogleClientId(res.data.clientId);
          setClientIdInput(res.data.clientId);
          localStorage.setItem('google_client_id', res.data.clientId);
        }
      })
      .catch(() => {});
  }, []);

  // Inicializa o botão oficial do Google Identity Services quando há um Client ID configurado
  useEffect(() => {
    if (!googleClientId || !window.google?.accounts?.id) return;

    try {
      window.google.accounts.id.initialize({
        client_id: googleClientId,
        callback: async (response: any) => {
          if (response?.credential) {
            try {
              setLoading(true);
              setErrorMsg(null);
              await loginWithGoogle(response.credential);
              navigate('/');
            } catch (err: any) {
              setErrorMsg(
                err.response?.data?.message || 'Erro ao autenticar com a credencial do Google.'
              );
            } finally {
              setLoading(false);
            }
          }
        },
      });

      if (googleBtnContainerRef.current) {
        window.google.accounts.id.renderButton(googleBtnContainerRef.current, {
          theme: 'outline',
          size: 'large',
          shape: 'pill',
          text: 'continue_with',
          width: 360,
          locale: 'pt-BR',
        });
      }
    } catch (e) {
      console.warn('Falha ao renderizar botão Google GIS:', e);
    }
  }, [googleClientId, loginWithGoogle, navigate]);

  /**
   * Aciona o popup OAuth 2.0 real do Google (accounts.google.com) solicitando:
   * - openid email profile (identidade real, nome, e-mail e foto)
   * - calendar.events.readonly (sincronização real de reuniões agendadas no Google Meet)
   */
  const handleGoogleOAuthPopup = () => {
    setErrorMsg(null);

    if (!googleClientId || !googleClientId.includes('.apps.googleusercontent.com')) {
      setShowConfigPanel(true);
      setErrorMsg(
        'Para abrir o seletor oficial de contas do Google, informe seu Google OAuth Client ID abaixo ou autentique com seu Token OAuth 2.0.'
      );
      return;
    }

    if (!window.google?.accounts?.oauth2) {
      setErrorMsg('SDK do Google Identity Services ainda está carregando. Tente novamente em instantes.');
      return;
    }

    try {
      const tokenClient = window.google.accounts.oauth2.initTokenClient({
        client_id: googleClientId.trim(),
        scope: 'openid email profile https://www.googleapis.com/auth/calendar.events.readonly',
        prompt: 'select_account',
        callback: async (tokenResponse: any) => {
          if (tokenResponse?.error) {
            setErrorMsg(`Erro retornado pelo Google OAuth: ${tokenResponse.error}`);
            return;
          }
          if (tokenResponse?.access_token) {
            try {
              setLoading(true);
              await loginWithGoogle(tokenResponse.access_token, tokenResponse.access_token);
              navigate('/');
            } catch (err: any) {
              setErrorMsg(
                err.response?.data?.message ||
                  'Não foi possível validar sua conta Google no servidor.'
              );
            } finally {
              setLoading(false);
            }
          }
        },
      });

      tokenClient.requestAccessToken();
    } catch (err: any) {
      setErrorMsg('Falha ao inicializar janela do Google OAuth. Verifique seu Client ID.');
    }
  };

  /**
   * Salva o Google OAuth Client ID no Backend e no navegador e já dispara o login real
   */
  const handleSaveClientId = async (e: React.FormEvent) => {
    e.preventDefault();
    const cleaned = clientIdInput.trim();
    if (!cleaned) return;

    try {
      await api.post('/auth/google/config', { clientId: cleaned });
      localStorage.setItem('google_client_id', cleaned);
      setGoogleClientId(cleaned);
      setErrorMsg(null);
      setConfigSavedMsg('Google OAuth Client ID salvo! Clique em "Entrar com Conta Google" para abrir a janela do Google.');
      setTimeout(() => setConfigSavedMsg(null), 5000);
    } catch (err) {
      localStorage.setItem('google_client_id', cleaned);
      setGoogleClientId(cleaned);
    }
  };

  /**
   * Login real imediato via Access Token OAuth 2.0 do Google (ya29.*)
   */
  const handleDirectGoogleTokenLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    const token = directTokenInput.trim();
    if (!token) return;

    try {
      setLoading(true);
      setErrorMsg(null);
      await loginWithGoogle(token, token);
      navigate('/');
    } catch (err: any) {
      setErrorMsg(
        err.response?.data?.message ||
          'Token OAuth 2.0 inválido ou expirado. Gere um novo token no Google OAuth Playground.'
      );
    } finally {
      setLoading(false);
    }
  };

  const handleDemoAccess = async () => {
    try {
      setLoading(true);
      setErrorMsg(null);
      await loginDemo();
      navigate('/');
    } catch (err) {
      setErrorMsg('Erro ao acessar ambiente de demonstração.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#f0f4f9] flex flex-col justify-center items-center p-6">
      <div className="w-full max-w-[480px] bg-white border border-[#e0e3e7] rounded-[28px] p-8 sm:p-10 shadow-xs space-y-6">
        {/* Identidade Visual Material 3 */}
        <div className="text-center space-y-2">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-[#0b57d0] text-white mb-1">
            <Video className="w-6 h-6" />
          </div>
          <h1 className="text-2xl font-normal tracking-tight text-[#1f1f1f]">
            Fazer login no <span className="font-semibold">Meet Intelligence</span>
          </h1>
          <p className="text-sm text-[#444746]">
            Autentique-se com sua conta real do Google para carregar seu perfil e sincronizar suas reuniões agendadas no Google Meet
          </p>
        </div>

        {errorMsg && (
          <div className="p-3.5 rounded-2xl bg-[#f9dedc] border border-[#f2b8b5] text-[#b3261e] text-xs flex items-start gap-2.5">
            <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
            <span>{errorMsg}</span>
          </div>
        )}

        {configSavedMsg && (
          <div className="p-3.5 rounded-2xl bg-[#e6f4ea] border border-[#c4eed0] text-[#146c2e] text-xs flex items-start gap-2.5">
            <CheckCircle2 className="w-4 h-4 shrink-0 mt-0.5" />
            <span>{configSavedMsg}</span>
          </div>
        )}

        {/* Botão Principal de Login Real com Conta do Google (OAuth 2.0 + Google Calendar Scope) */}
        <div className="space-y-3">
          <button
            type="button"
            onClick={handleGoogleOAuthPopup}
            disabled={loading}
            className="w-full flex items-center justify-center gap-3 py-3 px-5 rounded-full bg-[#0b57d0] hover:bg-[#0842a0] text-white font-medium text-sm shadow-xs transition-all cursor-pointer disabled:opacity-60"
          >
            <span className="w-6 h-6 rounded-full bg-white flex items-center justify-center shrink-0">
              <svg className="w-4 h-4" viewBox="0 0 24 24">
                <path
                  fill="#4285F4"
                  d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
                />
                <path
                  fill="#34A853"
                  d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
                />
                <path
                  fill="#FBBC05"
                  d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"
                />
                <path
                  fill="#EA4335"
                  d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"
                />
              </svg>
            </span>
            <span>{loading ? 'Autenticando no Google...' : 'Entrar com Conta Google (OAuth 2.0)'}</span>
          </button>

          {/* Container para o botão nativo do Google Identity Services quando Client ID está configurado */}
          {googleClientId && (
            <div ref={googleBtnContainerRef} className="flex justify-center pt-1" />
          )}

          {/* Botão para abrir/fechar configuração de Client ID ou Token OAuth Real */}
          <div className="flex items-center justify-between pt-1">
            <button
              type="button"
              onClick={() => setShowConfigPanel(!showConfigPanel)}
              className="text-xs font-medium text-[#0b57d0] hover:underline flex items-center gap-1.5 cursor-pointer"
            >
              <Settings className="w-3.5 h-3.5" />
              {showConfigPanel
                ? 'Ocultar configuração OAuth 2.0'
                : 'Configurar Client ID ou Token OAuth do Google'}
            </button>

            <button
              type="button"
              onClick={() => navigate('/landing')}
              className="text-xs font-medium text-[#0b57d0] hover:underline cursor-pointer"
            >
              Ver Apresentação & Comparativo
            </button>
          </div>
        </div>

        {/* Painel Expansível: Configuração Real do Google OAuth 2.0 */}
        {showConfigPanel && (
          <div className="p-4 rounded-2xl bg-[#f8fafd] border border-[#d3e3fd] space-y-5 text-left">
            {/* Opção A: Client ID do Google Cloud Console */}
            <form onSubmit={handleSaveClientId} className="space-y-2.5">
              <div className="flex items-center justify-between">
                <label className="text-xs font-semibold text-[#1f1f1f] flex items-center gap-1.5">
                  <KeyRound className="w-3.5 h-3.5 text-[#0b57d0]" />
                  Opção 1: Google OAuth Client ID (Popup Oficial)
                </label>
                <a
                  href="https://console.cloud.google.com/apis/credentials"
                  target="_blank"
                  rel="noreferrer"
                  className="text-[11px] text-[#0b57d0] hover:underline flex items-center gap-0.5"
                >
                  Google Cloud Console <ExternalLink className="w-3 h-3" />
                </a>
              </div>
              <p className="text-[11px] text-[#444746] leading-relaxed">
                Adicione <code className="bg-white px-1 py-0.5 rounded border border-[#e0e3e7]">http://localhost:5173</code> em <em>Origens JavaScript autorizadas</em> no seu Client ID Web do Google Cloud:
              </p>
              <div className="flex gap-2">
                <input
                  type="text"
                  placeholder="xxxx-xxxx.apps.googleusercontent.com"
                  value={clientIdInput}
                  onChange={(e) => setClientIdInput(e.target.value)}
                  className="md-input bg-white text-xs flex-1"
                />
                <button type="submit" className="md-btn-tonal !py-2 !px-3.5 !text-xs shrink-0">
                  Salvar Client ID
                </button>
              </div>
            </form>

            <div className="border-t border-[#e0e3e7]" />

            {/* Opção B: Access Token OAuth 2.0 Direto da Conta Google */}
            <form onSubmit={handleDirectGoogleTokenLogin} className="space-y-2.5">
              <div className="flex items-center justify-between">
                <label className="text-xs font-semibold text-[#1f1f1f]">
                  Opção 2: Entrar com Access Token Google (`ya29...`)
                </label>
                <a
                  href="https://developers.google.com/oauthplayground/?scope=openid%20email%20profile%20https://www.googleapis.com/auth/calendar.events.readonly"
                  target="_blank"
                  rel="noreferrer"
                  className="text-[11px] text-[#0b57d0] hover:underline flex items-center gap-0.5 font-medium"
                >
                  Gerar Token no Google Playground <ExternalLink className="w-3 h-3" />
                </a>
              </div>
              <p className="text-[11px] text-[#444746] leading-relaxed">
                Gere um token com sua conta real do Google no link acima (clique em <em>Authorize APIs</em> e depois em <em>Exchange authorization code for tokens</em>) e cole abaixo:
              </p>
              <div className="flex gap-2">
                <input
                  type="password"
                  placeholder="ya29.a0..."
                  value={directTokenInput}
                  onChange={(e) => setDirectTokenInput(e.target.value)}
                  className="md-input bg-white text-xs flex-1"
                />
                <button
                  type="submit"
                  disabled={loading || !directTokenInput.trim()}
                  className="md-btn-filled !py-2 !px-4 !text-xs shrink-0"
                >
                  Entrar
                </button>
              </div>
            </form>
          </div>
        )}

        {/* Selos Corporativos Material 3 */}
        <div className="pt-4 border-t border-[#e0e3e7] grid grid-cols-3 gap-2 text-center">
          <div className="p-2.5 rounded-xl bg-[#f8fafd]">
            <CalendarCheck className="w-4 h-4 text-[#0b57d0] mx-auto mb-1" />
            <span className="text-[11px] text-[#444746] block font-medium">Google Calendar</span>
          </div>
          <div className="p-2.5 rounded-xl bg-[#f8fafd]">
            <ShieldCheck className="w-4 h-4 text-[#146c2e] mx-auto mb-1" />
            <span className="text-[11px] text-[#444746] block font-medium">Conformidade LGPD</span>
          </div>
          <div className="p-2.5 rounded-xl bg-[#f8fafd]">
            <Lock className="w-4 h-4 text-[#444746] mx-auto mb-1" />
            <span className="text-[11px] text-[#444746] block font-medium">OAuth 2.0 Real</span>
          </div>
        </div>
      </div>

      <div className="mt-6 flex items-center gap-6 text-xs text-[#444746]">
        <span>Português (Brasil)</span>
        <span>•</span>
        <span>Privacidade & Governança</span>
        <span>•</span>
        <span>Termos Corporativos</span>
      </div>
    </div>
  );
};
