import React, { useEffect, useState } from 'react';
import { 
  ShieldCheck, 
  CreditCard, 
  Users, 
  Cpu, 
  Clock, 
  CheckCircle2, 
  Sparkles, 
  UserPlus, 
  Database, 
  Trash2, 
  Lock,
  Key,
  Bot,
  Zap,
  Check,
  AlertCircle,
  Eye,
  EyeOff
} from 'lucide-react';
import { api } from '../services/api';

interface PlanDetail {
  id: 'FREE' | 'PRO' | 'BUSINESS';
  name: string;
  price: string;
  minutes: string;
  features: string[];
  recommended?: boolean;
}

const PLANS: PlanDetail[] = [
  {
    id: 'FREE',
    name: 'Starter',
    price: 'R$ 0 / mês',
    minutes: '5 horas / mês',
    features: [
      'Gravação e transcrição básica',
      'Até 15 reuniões mensais',
      'Resumos executivos automáticos',
      'Exportação em TXT',
      'Histórico retido por 14 dias',
    ],
  },
  {
    id: 'PRO',
    name: 'Profissional',
    price: 'R$ 149 / mês',
    minutes: '50 horas / mês',
    features: [
      'Tudo do Starter',
      'Até 120 reuniões mensais',
      'Assistente RAG com busca vetorial',
      'Exportação em DOCX e PDF formal',
      'Perfis de IA adaptativos customizados',
      'Histórico retido por 90 dias',
    ],
    recommended: true,
  },
  {
    id: 'BUSINESS',
    name: 'Enterprise / Squads',
    price: 'R$ 499 / mês',
    minutes: '500 horas / mês',
    features: [
      'Reuniões ilimitadas',
      'Diarização avançada por orador',
      'Múltiplos workspaces e permissões RBAC',
      'Suporte prioritário e SLAs dedicados',
      'Políticas avançadas de retenção LGPD',
      'Retenção customizável até 5 anos',
    ],
  },
];

export const SettingsPage: React.FC = () => {
  const [metrics, setMetrics] = useState<any>(null);
  const [organization, setOrganization] = useState<any>(null);
  const [aiConfig, setAiConfig] = useState<any>(null);
  const [, setLoading] = useState(true);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('MEMBER');
  const [updatingPlan, setUpdatingPlan] = useState(false);
  const [feedbackMsg, setFeedbackMsg] = useState<string | null>(null);

  // Estados da Configuração de IA (BYOK)
  const [selectedProvider, setSelectedProvider] = useState<'GEMINI' | 'GROK' | 'CLAUDE'>('GEMINI');
  const [geminiKey, setGeminiKey] = useState('');
  const [geminiModel, setGeminiModel] = useState('gemini-3.5-flash-lite');
  const [grokKey, setGrokKey] = useState('');
  const [grokModel, setGrokModel] = useState('grok-4.7');
  const [claudeKey, setClaudeKey] = useState('');
  const [claudeModel, setClaudeModel] = useState('claude-opus-5-5');
  const [showKeys, setShowKeys] = useState<{ [key: string]: boolean }>({});
  const [testingConnection, setTestingConnection] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ [key: string]: any }>({});
  const [savingAi, setSavingAi] = useState(false);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    try {
      setLoading(true);
      const [resMetrics, resOrg, resAi] = await Promise.all([
        api.get('/dashboard/metrics'),
        api.get('/organization'),
        api.get('/organization/ai-config'),
      ]);
      setMetrics(resMetrics.data);
      setOrganization(resOrg.data);
      
      if (resAi.data) {
        setAiConfig(resAi.data);
        setSelectedProvider(resAi.data.activeProvider || 'GEMINI');
        setGeminiKey(resAi.data.geminiMaskedKey || '');
        setGeminiModel(resAi.data.geminiModel || 'gemini-3.5-flash-lite');
        setGrokKey(resAi.data.grokMaskedKey || '');
        setGrokModel(resAi.data.grokModel || 'grok-4.7');
        setClaudeKey(resAi.data.claudeMaskedKey || '');
        setClaudeModel(resAi.data.claudeModel || 'claude-opus-5-5');
      }
    } catch (err) {
      console.error('Erro ao carregar dados do tenant:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleUpdatePlan = async (planType: string) => {
    try {
      setUpdatingPlan(true);
      await api.patch(`/organization/plan?planType=${planType}`);
      setFeedbackMsg(`Plano atualizado com sucesso para ${planType}!`);
      setTimeout(() => setFeedbackMsg(null), 4000);
      await loadData();
    } catch (err) {
      console.error('Erro ao alterar plano:', err);
      alert('Não foi possível alterar o plano no momento.');
    } finally {
      setUpdatingPlan(false);
    }
  };

  const handleInviteMember = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!inviteEmail) return;

    try {
      await api.post('/organization/members/invite', {
        email: inviteEmail,
        role: inviteRole,
      });
      setInviteEmail('');
      setFeedbackMsg(`Convite enviado com sucesso para ${inviteEmail}!`);
      setTimeout(() => setFeedbackMsg(null), 4000);
      await loadData();
    } catch (err) {
      console.error('Erro ao convidar membro:', err);
      alert('Não foi possível convidar o membro.');
    }
  };

  const handleSaveAiConfig = async () => {
    try {
      setSavingAi(true);
      await api.put('/organization/ai-config', {
        activeProvider: selectedProvider,
        geminiApiKey: geminiKey,
        geminiModel,
        grokApiKey: grokKey,
        grokModel,
        claudeApiKey: claudeKey,
        claudeModel,
        useOrganizationKeys: true,
      });
      setFeedbackMsg(`Configuração de IA salva! O provedor ativo agora é: ${selectedProvider}`);
      setTimeout(() => setFeedbackMsg(null), 5000);
      await loadData();
    } catch (err) {
      console.error('Erro ao salvar configuração de IA:', err);
      alert('Não foi possível salvar a configuração de IA.');
    } finally {
      setSavingAi(false);
    }
  };

  const handleTestConnection = async (provider: 'GEMINI' | 'GROK' | 'CLAUDE') => {
    try {
      setTestingConnection(provider);
      let keyToTest = '';
      let modelToTest = '';

      if (provider === 'GEMINI') {
        keyToTest = geminiKey;
        modelToTest = geminiModel;
      } else if (provider === 'GROK') {
        keyToTest = grokKey;
        modelToTest = grokModel;
      } else if (provider === 'CLAUDE') {
        keyToTest = claudeKey;
        modelToTest = claudeModel;
      }

      const res = await api.post('/organization/ai-config/test', {
        provider,
        apiKey: keyToTest,
        model: modelToTest,
      });

      setTestResult((prev) => ({ ...prev, [provider]: res.data }));
    } catch (err: any) {
      setTestResult((prev) => ({
        ...prev,
        [provider]: {
          success: false,
          message: err.response?.data?.message || 'Falha de rede ao conectar com a API.',
        },
      }));
    } finally {
      setTestingConnection(null);
    }
  };

  const toggleShowKey = (provider: string) => {
    setShowKeys((prev) => ({ ...prev, [provider]: !prev[provider] }));
  };

  const currentPlan = organization?.planType || 'FREE';

  return (
    <div className="p-8 space-y-8 max-w-7xl mx-auto w-full">
      {/* Cabeçalho */}
      <div>
        <h1 className="text-2xl font-normal tracking-tight text-[#1f1f1f]">Configurações & Gestão SaaS</h1>
        <p className="text-sm text-[#444746] mt-1">
          Configure sua própria IA (Grok, Claude ou Gemini), gerencie limites de processamento, membros e governança.
        </p>
      </div>

      {feedbackMsg && (
        <div className="p-4 rounded-xl bg-[#e6f4ea] border border-[#c4eed0] text-[#146c2e] text-sm flex items-center gap-2">
          <CheckCircle2 className="w-5 h-5 shrink-0" />
          <span>{feedbackMsg}</span>
        </div>
      )}

      {/* SEÇÃO PRINCIPAL: CONFIGURAÇÃO DE IA DO CLIENTE (BYOK - Bring Your Own Key) */}
      <div className="md-card p-6 space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-[#e0e3e7] pb-4">
          <div>
            <div className="flex items-center gap-2 text-[#0b57d0] font-semibold text-xs uppercase tracking-wider">
              <Bot className="w-4 h-4" />
              Motor de IA Corporativo • BYOK (Bring Your Own Key)
            </div>
            <h2 className="text-xl font-normal text-[#1f1f1f] mt-1">
              Escolha e Configure a IA da Sua Empresa
            </h2>
            <p className="text-xs text-[#444746] mt-1">
              O Meet Intelligence suporta Google Gemini, xAI Grok e Anthropic Claude. Configure sua própria chave de API para controle total de privacidade e faturamento.
            </p>
          </div>

          <button
            onClick={handleSaveAiConfig}
            disabled={savingAi}
            className="md-btn-filled shrink-0 self-start sm:self-auto"
          >
            <Check className="w-4 h-4" />
            {savingAi ? 'Salvando...' : 'Salvar Preferências de IA'}
          </button>
        </div>

        {/* Seletor Visual dos 3 Provedores */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {/* CARD 1: GOOGLE GEMINI */}
          <div
            onClick={() => setSelectedProvider('GEMINI')}
            className={`p-5 rounded-2xl border cursor-pointer transition-all flex flex-col justify-between ${
              selectedProvider === 'GEMINI'
                ? 'bg-[#e8f0fe] border-[#0b57d0] ring-2 ring-[#0b57d0]/15'
                : 'bg-white border-[#e0e3e7] hover:border-[#c4c7c5]'
            }`}
          >
            <div>
              <div className="flex items-center justify-between">
                <span className="font-medium text-sm text-[#1f1f1f] flex items-center gap-2">
                  <span className="w-2.5 h-2.5 rounded-full bg-[#0b57d0]"></span>
                  Google Gemini
                </span>
                {selectedProvider === 'GEMINI' && (
                  <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#0b57d0] text-white uppercase tracking-wider">
                    IA Ativa
                  </span>
                )}
              </div>
              <p className="text-xs text-[#444746] mt-2 leading-relaxed">
                Janela de contexto de 1 milhão de tokens. Ideal para reuniões longas e síntese em tempo real.
              </p>
            </div>

            <div className="mt-4 pt-3 border-t border-[#e0e3e7] flex items-center justify-between text-[11px]">
              <span className="text-[#444746]">Status da Chave:</span>
              <span className={`font-semibold ${aiConfig?.geminiConfigured ? 'text-[#146c2e]' : 'text-[#b06000]'}`}>
                {aiConfig?.geminiConfigured ? 'Chave do Cliente' : 'Chave Padrão'}
              </span>
            </div>
          </div>

          {/* CARD 2: XAI GROK */}
          <div
            onClick={() => setSelectedProvider('GROK')}
            className={`p-5 rounded-2xl border cursor-pointer transition-all flex flex-col justify-between ${
              selectedProvider === 'GROK'
                ? 'bg-[#e6f4ea] border-[#146c2e] ring-2 ring-[#146c2e]/15'
                : 'bg-white border-[#e0e3e7] hover:border-[#c4c7c5]'
            }`}
          >
            <div>
              <div className="flex items-center justify-between">
                <span className="font-medium text-sm text-[#1f1f1f] flex items-center gap-2">
                  <span className="w-2.5 h-2.5 rounded-full bg-[#146c2e]"></span>
                  xAI Grok
                </span>
                {selectedProvider === 'GROK' && (
                  <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#146c2e] text-white uppercase tracking-wider">
                    IA Ativa
                  </span>
                )}
              </div>
              <p className="text-xs text-[#444746] mt-2 leading-relaxed">
                Raciocínio forte e respostas diretas, com bom desempenho em perguntas técnicas e de arquitetura.
                A mesma chave também faz a transcrição do áudio da reunião.
              </p>
            </div>

            <div className="mt-4 pt-3 border-t border-[#e0e3e7] flex items-center justify-between text-[11px]">
              <span className="text-[#444746]">Status da Chave:</span>
              <span className={`font-semibold ${aiConfig?.grokConfigured ? 'text-[#146c2e]' : 'text-[#b06000]'}`}>
                {aiConfig?.grokConfigured ? 'Chave do Cliente' : 'Chave Padrão'}
              </span>
            </div>
          </div>

          {/* CARD 3: ANTHROPIC CLAUDE */}
          <div
            onClick={() => setSelectedProvider('CLAUDE')}
            className={`p-5 rounded-2xl border cursor-pointer transition-all flex flex-col justify-between ${
              selectedProvider === 'CLAUDE'
                ? 'bg-[#f3e8fd] border-[#6b4ea2] ring-2 ring-[#6b4ea2]/15'
                : 'bg-white border-[#e0e3e7] hover:border-[#c4c7c5]'
            }`}
          >
            <div>
              <div className="flex items-center justify-between">
                <span className="font-medium text-sm text-[#1f1f1f] flex items-center gap-2">
                  <span className="w-2.5 h-2.5 rounded-full bg-[#6b4ea2]"></span>
                  Anthropic Claude
                </span>
                {selectedProvider === 'CLAUDE' && (
                  <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#6b4ea2] text-white uppercase tracking-wider">
                    IA Ativa
                  </span>
                )}
              </div>
              <p className="text-xs text-[#444746] mt-2 leading-relaxed">
                Especialista em nuances conversacionais, clareza técnica e elaboração de atas executivas formais.
              </p>
            </div>

            <div className="mt-4 pt-3 border-t border-[#e0e3e7] flex items-center justify-between text-[11px]">
              <span className="text-[#444746]">Status da Chave:</span>
              <span className={`font-semibold ${aiConfig?.claudeConfigured ? 'text-[#146c2e]' : 'text-[#b06000]'}`}>
                {aiConfig?.claudeConfigured ? 'Chave do Cliente' : 'Chave Padrão'}
              </span>
            </div>
          </div>
        </div>

        {/* Formulários de Configuração e Teste de Chave */}
        <div className="space-y-4 pt-2">
          {/* CONFIGURAÇÃO DO GEMINI */}
          {selectedProvider === 'GEMINI' && (
            <div className="p-5 rounded-2xl bg-[#f8fafd] border border-[#e0e3e7] space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
                  <Key className="w-4 h-4 text-[#0b57d0]" />
                  Credenciais Google Gemini
                </h3>
                <span className="text-xs text-[#444746]">Obtenha sua chave em aistudio.google.com</span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="text-xs font-medium text-[#444746] block mb-1.5">
                    API Key da Google
                  </label>
                  <div className="relative">
                    <input
                      type={showKeys['GEMINI'] ? 'text' : 'password'}
                      placeholder="AIzaSy..."
                      value={geminiKey}
                      onChange={(e) => setGeminiKey(e.target.value)}
                      className="md-input bg-white pr-10"
                    />
                    <button
                      type="button"
                      onClick={() => toggleShowKey('GEMINI')}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-[#444746] hover:text-[#1f1f1f]"
                    >
                      {showKeys['GEMINI'] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                </div>

                <div>
                  <label className="text-xs font-medium text-[#444746] block mb-1.5">
                    Modelo do Gemini
                  </label>
                  <select
                    value={geminiModel}
                    onChange={(e) => setGeminiModel(e.target.value)}
                    className="md-input bg-white"
                  >
                    <option value="gemini-3.5-flash-lite">Gemini 3.5 Flash-Lite (Ultrarrápido, menor custo, Recomendado)</option>
                    <option value="gemini-4-hydrogen">Gemini 4 Hydrogen (Qualidade Superior)</option>
                    <option value="gemini-3.8-flash">Gemini 3.8 Flash (Equilíbrio entre qualidade e velocidade)</option>
                  </select>
                </div>
              </div>

              <div className="flex items-center justify-between pt-2">
                <button
                  type="button"
                  onClick={() => handleTestConnection('GEMINI')}
                  disabled={testingConnection === 'GEMINI'}
                  className="md-btn-tonal"
                >
                  <Zap className="w-3.5 h-3.5 text-[#0b57d0]" />
                  {testingConnection === 'GEMINI' ? 'Testando conexão...' : 'Testar Conexão com Gemini'}
                </button>

                {testResult['GEMINI'] && (
                  <div className={`text-xs flex items-center gap-1.5 font-medium ${testResult['GEMINI'].success ? 'text-[#146c2e]' : 'text-[#b3261e]'}`}>
                    {testResult['GEMINI'].success ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
                    <span>{testResult['GEMINI'].message} ({testResult['GEMINI'].latencyMs}ms)</span>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* CONFIGURAÇÃO DO GROK (xAI) */}
          {selectedProvider === 'GROK' && (
            <div className="p-5 rounded-2xl bg-[#f8fafd] border border-[#e0e3e7] space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
                  <Key className="w-4 h-4 text-[#146c2e]" />
                  Credenciais xAI Grok
                </h3>
                <span className="text-xs text-[#444746]">Obtenha sua chave em console.x.ai</span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="text-xs font-medium text-[#444746] block mb-1.5">
                    API Key da xAI
                  </label>
                  <div className="relative">
                    <input
                      type={showKeys['GROK'] ? 'text' : 'password'}
                      placeholder="xai-..."
                      value={grokKey}
                      onChange={(e) => setGrokKey(e.target.value)}
                      className="md-input bg-white pr-10"
                    />
                    <button
                      type="button"
                      onClick={() => toggleShowKey('GROK')}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-[#444746] hover:text-[#1f1f1f]"
                    >
                      {showKeys['GROK'] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                </div>

                <div>
                  <label className="text-xs font-medium text-[#444746] block mb-1.5">
                    Modelo Grok
                  </label>
                  <select
                    value={grokModel}
                    onChange={(e) => setGrokModel(e.target.value)}
                    className="md-input bg-white"
                  >
                    <option value="grok-4.7">Grok 4.7 (Máxima Capacidade, Recomendado)</option>
                    <option value="grok-4.3">Grok 4.3 (Menor custo)</option>
                    <option value="grok-4.20-0309-non-reasoning">Grok 4.20 sem raciocínio (Menor latência)</option>
                  </select>
                </div>
              </div>

              <div className="flex items-center justify-between pt-2">
                <button
                  type="button"
                  onClick={() => handleTestConnection('GROK')}
                  disabled={testingConnection === 'GROK'}
                  className="md-btn-tonal"
                >
                  <Zap className="w-3.5 h-3.5 text-[#0b57d0]" />
                  {testingConnection === 'GROK' ? 'Testando conexão...' : 'Testar Conexão com Grok'}
                </button>

                {testResult['GROK'] && (
                  <div className={`text-xs flex items-center gap-1.5 font-medium ${testResult['GROK'].success ? 'text-[#146c2e]' : 'text-[#b3261e]'}`}>
                    {testResult['GROK'].success ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
                    <span>{testResult['GROK'].message} ({testResult['GROK'].latencyMs}ms)</span>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* CONFIGURAÇÃO DO CLAUDE */}
          {selectedProvider === 'CLAUDE' && (
            <div className="p-5 rounded-2xl bg-[#f8fafd] border border-[#e0e3e7] space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
                  <Key className="w-4 h-4 text-[#6b4ea2]" />
                  Credenciais Anthropic Claude
                </h3>
                <span className="text-xs text-[#444746]">Obtenha sua chave em console.anthropic.com</span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="text-xs font-medium text-[#444746] block mb-1.5">
                    API Key da Anthropic
                  </label>
                  <div className="relative">
                    <input
                      type={showKeys['CLAUDE'] ? 'text' : 'password'}
                      placeholder="sk-ant-..."
                      value={claudeKey}
                      onChange={(e) => setClaudeKey(e.target.value)}
                      className="md-input bg-white pr-10"
                    />
                    <button
                      type="button"
                      onClick={() => toggleShowKey('CLAUDE')}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-[#444746] hover:text-[#1f1f1f]"
                    >
                      {showKeys['CLAUDE'] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                </div>

                <div>
                  <label className="text-xs font-medium text-[#444746] block mb-1.5">
                    Modelo Claude
                  </label>
                  <select
                    value={claudeModel}
                    onChange={(e) => setClaudeModel(e.target.value)}
                    className="md-input bg-white"
                  >
                    <option value="claude-opus-5-5">Claude Opus 5.5 (Qualidade Superior, Recomendado)</option>
                    <option value="claude-sonnet-5-5">Claude Sonnet 5.5 (Equilíbrio entre qualidade e velocidade)</option>
                    <option value="claude-haiku-5-5">Claude Haiku 5.5 (Baixa Latência, menor custo)</option>
                  </select>
                </div>
              </div>

              <div className="flex items-center justify-between pt-2">
                <button
                  type="button"
                  onClick={() => handleTestConnection('CLAUDE')}
                  disabled={testingConnection === 'CLAUDE'}
                  className="md-btn-tonal"
                >
                  <Zap className="w-3.5 h-3.5 text-[#0b57d0]" />
                  {testingConnection === 'CLAUDE' ? 'Testando conexão...' : 'Testar Conexão com Claude'}
                </button>

                {testResult['CLAUDE'] && (
                  <div className={`text-xs flex items-center gap-1.5 font-medium ${testResult['CLAUDE'].success ? 'text-[#146c2e]' : 'text-[#b3261e]'}`}>
                    {testResult['CLAUDE'].success ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
                    <span>{testResult['CLAUDE'].message} ({testResult['CLAUDE'].latencyMs}ms)</span>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Cartões de Quotas e Uso do Tenant */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="md-card p-5 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[#444746]">
            <span className="text-xs font-medium">Uso da Quota Mensal</span>
            <Clock className="w-4 h-4 text-[#0b57d0]" />
          </div>
          <div className="mt-4">
            <div className="text-2xl font-normal text-[#1f1f1f]">
              {metrics?.usedMinutesThisMonth || 0} / {metrics?.monthlyMinutesLimit || 300} min
            </div>
            <div className="w-full bg-[#e0e3e7] rounded-full h-2 mt-3 overflow-hidden">
              <div
                className="bg-[#0b57d0] h-2 rounded-full transition-all duration-500"
                style={{ width: `${Math.min(100, metrics?.quotaUsagePercentage || 0)}%` }}
              />
            </div>
            <p className="text-[11px] text-[#444746] mt-2">
              {metrics?.quotaUsagePercentage || 0}% consumido este mês
            </p>
          </div>
        </div>

        <div className="md-card p-5 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[#444746]">
            <span className="text-xs font-medium">Total Gravado / Transcrito</span>
            <Database className="w-4 h-4 text-[#146c2e]" />
          </div>
          <div className="mt-4">
            <div className="text-2xl font-normal text-[#1f1f1f]">
              {metrics?.recordedHours || 0} horas
            </div>
            <p className="text-xs text-[#444746] mt-1">
              {metrics?.transcribedHours || 0} horas transcritas via STT
            </p>
            <p className="text-[11px] text-[#146c2e] font-medium mt-2">
              {metrics?.completedMeetings || 0} reuniões concluídas
            </p>
          </div>
        </div>

        <div className="md-card p-5 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[#444746]">
            <span className="text-xs font-medium">Consumo de IA & Tokens</span>
            <Cpu className="w-4 h-4 text-[#00639b]" />
          </div>
          <div className="mt-4">
            <div className="text-2xl font-normal text-[#1f1f1f]">
              {metrics?.totalTokensConsumed ? metrics.totalTokensConsumed.toLocaleString() : '0'} tokens
            </div>
            <p className="text-xs text-[#444746] mt-1">
              {metrics?.totalLlmCalls || 0} chamadas ao LLM Orchestrator
            </p>
            <p className="text-[11px] text-[#0b57d0] font-medium mt-2">
              Custo est.: ${metrics?.totalCostUsd || '0.00'} USD
            </p>
          </div>
        </div>

        <div className="md-card p-5 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[#444746]">
            <span className="text-xs font-medium">Insights & Produtividade</span>
            <Sparkles className="w-4 h-4 text-[#0b57d0]" />
          </div>
          <div className="mt-4">
            <div className="text-2xl font-normal text-[#1f1f1f]">
              {metrics?.totalInsightsGenerated || 0}
            </div>
            <p className="text-xs text-[#444746] mt-1">
              {metrics?.totalDecisions || 0} decisões • {metrics?.totalPendingTasks || 0} tarefas
            </p>
            <p className="text-[11px] text-[#0b57d0] font-medium mt-2">
              Extração contínua ativa
            </p>
          </div>
        </div>
      </div>

      {/* Planos de Assinatura */}
      <div className="space-y-4">
        <div>
          <h2 className="text-lg font-medium text-[#1f1f1f] flex items-center gap-2">
            <CreditCard className="w-5 h-5 text-[#0b57d0]" />
            Planos de Assinatura & Quotas
          </h2>
          <p className="text-xs text-[#444746]">
            Alterne instantaneamente entre planos conforme a demanda da sua equipe.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {PLANS.map((plan) => {
            const isCurrent = currentPlan === plan.id;
            return (
              <div
                key={plan.id}
                className={`p-6 rounded-2xl border flex flex-col justify-between transition-all relative ${
                  isCurrent
                    ? 'bg-[#f8fafd] border-[#0b57d0] ring-2 ring-[#0b57d0]/15'
                    : 'bg-white border-[#e0e3e7] hover:border-[#c4c7c5]'
                }`}
              >
                {plan.recommended && (
                  <span className="absolute -top-3 left-6 px-3 py-0.5 rounded-full bg-[#0b57d0] text-[10px] font-semibold text-white uppercase tracking-wider">
                    Mais Popular
                  </span>
                )}

                <div>
                  <div className="flex items-center justify-between">
                    <h3 className="text-base font-medium text-[#1f1f1f]">{plan.name}</h3>
                    {isCurrent && (
                      <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#e6f4ea] text-[#146c2e]">
                        Plano Atual
                      </span>
                    )}
                  </div>

                  <div className="mt-3">
                    <div className="text-2xl font-normal text-[#1f1f1f]">{plan.price}</div>
                    <div className="text-xs text-[#0b57d0] font-medium mt-1">{plan.minutes}</div>
                  </div>

                  <div className="mt-6 space-y-2.5">
                    {plan.features.map((feat, idx) => (
                      <div key={idx} className="flex items-start gap-2 text-xs text-[#444746]">
                        <CheckCircle2 className="w-4 h-4 text-[#146c2e] shrink-0 mt-0.5" />
                        <span>{feat}</span>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="mt-8">
                  <button
                    disabled={isCurrent || updatingPlan}
                    onClick={() => handleUpdatePlan(plan.id)}
                    className={`w-full py-2.5 px-4 rounded-full text-xs font-medium transition-all ${
                      isCurrent
                        ? 'bg-[#e0e3e7] text-[#747775] cursor-not-allowed'
                        : 'bg-[#0b57d0] hover:bg-[#0842a0] text-white shadow-sm'
                    }`}
                  >
                    {isCurrent ? 'Plano Ativo' : `Migrar para ${plan.name}`}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Gestão de Membros da Equipe */}
      <div className="space-y-4">
        <div>
          <h2 className="text-lg font-medium text-[#1f1f1f] flex items-center gap-2">
            <Users className="w-5 h-5 text-[#0b57d0]" />
            Membros da Organização ({organization?.name || 'Workspace'})
          </h2>
          <p className="text-xs text-[#444746]">
            Controle de acesso por função (Role-Based Access Control).
          </p>
        </div>

        <div className="md-card p-6 space-y-6">
          <form onSubmit={handleInviteMember} className="flex flex-col sm:flex-row gap-3">
            <input
              type="email"
              required
              placeholder="Digite o e-mail do colaborador..."
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              className="md-input flex-1"
            />
            <select
              value={inviteRole}
              onChange={(e) => setInviteRole(e.target.value)}
              className="md-input sm:w-52"
            >
              <option value="MEMBER">Membro (Editor)</option>
              <option value="ADMIN">Administrador</option>
              <option value="VIEWER">Visualizador</option>
            </select>
            <button
              type="submit"
              className="md-btn-filled shrink-0"
            >
              <UserPlus className="w-4 h-4" />
              Convidar Membro
            </button>
          </form>

          <div className="divide-y divide-[#e0e3e7]">
            {organization?.members?.map((mem: any) => (
              <div key={mem.userId} className="py-3.5 flex items-center justify-between text-xs">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-full bg-[#d3e3fd] text-[#041e49] flex items-center justify-center font-semibold">
                    {mem.fullName ? mem.fullName.charAt(0).toUpperCase() : 'U'}
                  </div>
                  <div>
                    <div className="font-medium text-[#1f1f1f]">{mem.fullName || 'Usuário'}</div>
                    <div className="text-[#444746] text-[11px]">{mem.email}</div>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#f0f4f9] text-[#444746] border border-[#e0e3e7] uppercase">
                    {mem.role}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Privacidade, LGPD & Governança */}
      <div className="space-y-4">
        <div>
          <h2 className="text-lg font-medium text-[#1f1f1f] flex items-center gap-2">
            <Lock className="w-5 h-5 text-[#0b57d0]" />
            Conformidade LGPD & Governança de Dados
          </h2>
          <p className="text-xs text-[#444746]">
            Políticas de privacidade, direito ao esquecimento e descarte seguro de dados.
          </p>
        </div>

        <div className="md-card p-6">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
            <div className="p-4 rounded-xl bg-[#f8fafd] border border-[#e0e3e7] space-y-2">
              <div className="font-medium text-[#1f1f1f] flex items-center gap-2">
                <ShieldCheck className="w-4 h-4 text-[#146c2e]" />
                Criptografia em Repouso e em Trânsito
              </div>
              <p className="text-[#444746] leading-relaxed text-[11px]">
                Todos os áudios e transcrições são transmitidos via TLS 1.3 e armazenados em buckets S3 protegidos com SSE-S3. Os embeddings vetoriais residem isolados por tenant ID no pgvector.
              </p>
            </div>

            <div className="p-4 rounded-xl bg-[#f8fafd] border border-[#e0e3e7] space-y-2">
              <div className="font-medium text-[#1f1f1f] flex items-center gap-2">
                <Trash2 className="w-4 h-4 text-[#b3261e]" />
                Direito ao Esquecimento (Art. 18 LGPD)
              </div>
              <p className="text-[#444746] leading-relaxed text-[11px]">
                A exclusão de qualquer reunião dispara o processo em cascata: os arquivos de mídia são permanentemente eliminados do S3, registros de banco de dados são expurgados e os vetores no pgvector são desalocados imediatamente.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
