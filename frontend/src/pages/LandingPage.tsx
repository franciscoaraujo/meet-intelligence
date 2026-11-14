import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Video,
  ShieldCheck,
  Server,
  Cpu,
  Lock,
  CheckCircle2,
  XCircle,
  ArrowRight,
  Sparkles,
  FileText,
  Users,
  Building2,
  KeyRound,
  Database,
  Globe,
  Zap,
  Award,
  ChevronRight,
  Scale,
  Code2,
  Briefcase,
  LayoutDashboard,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';

export const LandingPage: React.FC = () => {
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();
  const [teamSize, setTeamSize] = useState<number>(15);

  // Cálculo comparativo: concorrentes cobram ~US$ 29/usuário/mês (~R$ 165/mês por pessoa)
  const competitorMonthlyBrl = teamSize * 165;
  const competitorYearlyBrl = competitorMonthlyBrl * 12;

  const handlePrimaryAction = () => {
    if (isAuthenticated) {
      navigate('/');
    } else {
      navigate('/login');
    }
  };

  return (
    <div className="min-h-screen bg-[#f8fafd] text-[#1f1f1f] overflow-x-hidden selection:bg-[#d3e3fd] selection:text-[#041e49]">
      {/* Top Navbar Fixa — Padrão Google Workspace Enterprise */}
      <header className="sticky top-0 z-50 h-16 px-6 lg:px-12 bg-white/90 backdrop-blur-md border-b border-[#e0e3e7] flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-[#0b57d0] flex items-center justify-center text-white shadow-xs">
            <Video className="w-5 h-5" />
          </div>
          <div>
            <span className="font-semibold text-[17px] tracking-tight text-[#1f1f1f] block leading-tight">
              Meet Intelligence
            </span>
            <span className="text-[11px] text-[#0b57d0] font-semibold block">
              Sovereign AI & LGPD Ready
            </span>
          </div>
        </div>

        <nav className="hidden md:flex items-center gap-7 text-xs font-medium text-[#444746]">
          <a href="#diferenciais" className="hover:text-[#0b57d0] transition-colors">
            Por que mudar?
          </a>
          <a href="#comparativo" className="hover:text-[#0b57d0] transition-colors">
            Comparativo Técnico
          </a>
          <a href="#ia-local" className="hover:text-[#0b57d0] transition-colors">
            IA Local & LGPD
          </a>
          <a href="#nichos" className="hover:text-[#0b57d0] transition-colors">
            Casos de Uso
          </a>
          <a href="#economia" className="hover:text-[#0b57d0] transition-colors">
            Calculadora ROI
          </a>
        </nav>

        <div className="flex items-center gap-3">
          {isAuthenticated ? (
            <button onClick={() => navigate('/')} className="md-btn-filled !py-2 !px-5 !text-xs">
              <LayoutDashboard className="w-3.5 h-3.5" />
              <span>Voltar ao Painel</span>
            </button>
          ) : (
            <>
              <button
                onClick={() => navigate('/login')}
                className="md-btn-outlined !py-2 !px-4 !text-xs"
              >
                Entrar com Google
              </button>
              <button
                onClick={() => navigate('/login')}
                className="md-btn-filled !py-2 !px-5 !text-xs"
              >
                <span>Testar Plataforma</span>
                <ArrowRight className="w-3.5 h-3.5" />
              </button>
            </>
          )}
        </div>
      </header>

      {/* HERO SECTION */}
      <section className="relative pt-14 pb-20 px-6 lg:px-12 max-w-7xl mx-auto">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          <div className="lg:col-span-7 space-y-6">
            <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#e8f0fe] border border-[#d3e3fd] text-[#0b57d0] text-xs font-semibold">
              <ShieldCheck className="w-4 h-4" />
              <span>A única plataforma de Inteligência para Google Meet com IA Local & BYOK</span>
            </div>

            <h1 className="text-3xl sm:text-5xl font-semibold tracking-tight text-[#1f1f1f] leading-[1.15]">
              Transforme reuniões em atas, pareceres e decisões{' '}
              <span className="text-[#0b57d0]">sem entregar seus dados para terceiros.</span>
            </h1>

            <p className="text-base sm:text-lg text-[#444746] leading-relaxed max-w-2xl">
              Enquanto ferramentas comuns enviam o áudio confidencial da sua empresa para servidores
              compartilhados no exterior e cobram em dólar por usuário, o{' '}
              <strong className="text-[#1f1f1f]">Meet Intelligence</strong> roda na{' '}
              <strong className="text-[#1f1f1f]">sua infraestrutura local (On-Premise)</strong> ou com{' '}
              <strong className="text-[#1f1f1f]">sua própria chave de IA (Gemini, OpenAI ou Claude)</strong>.
            </p>

            <div className="flex flex-wrap items-center gap-3.5 pt-2">
              <button
                onClick={handlePrimaryAction}
                className="px-7 py-3.5 rounded-full bg-[#0b57d0] hover:bg-[#0842a0] text-white font-semibold text-sm flex items-center gap-2.5 shadow-sm transition-all cursor-pointer"
              >
                <span>{isAuthenticated ? 'Abrir Minha Agenda Sincronizada' : 'Começar Agora com Google Meet'}</span>
                <ArrowRight className="w-4 h-4" />
              </button>

              <a
                href="#comparativo"
                className="px-6 py-3.5 rounded-full bg-white hover:bg-[#edf2fc] text-[#1f1f1f] border border-[#c4c7c5] font-medium text-sm transition-colors"
              >
                Ver Comparativo com Concorrentes
              </a>
            </div>

            {/* Selos de Confiança */}
            <div className="pt-4 grid grid-cols-3 gap-4 border-t border-[#e0e3e7] max-w-xl">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-[#e6f4ea] text-[#146c2e] flex items-center justify-center shrink-0">
                  <Lock className="w-4 h-4" />
                </div>
                <div>
                  <p className="text-xs font-semibold text-[#1f1f1f]">100% LGPD</p>
                  <p className="text-[11px] text-[#444746]">Zero vazamento externo</p>
                </div>
              </div>

              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-[#e8f0fe] text-[#0b57d0] flex items-center justify-center shrink-0">
                  <Cpu className="w-4 h-4" />
                </div>
                <div>
                  <p className="text-xs font-semibold text-[#1f1f1f]">Multi-LLM & Local</p>
                  <p className="text-[11px] text-[#444746]">Ollama, Gemini, GPT, Claude</p>
                </div>
              </div>

              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-[#fef7e0] text-[#b06000] flex items-center justify-center shrink-0">
                  <Database className="w-4 h-4" />
                </div>
                <div>
                  <p className="text-xs font-semibold text-[#1f1f1f]">RAG com pgvector</p>
                  <p className="text-[11px] text-[#444746]">Citações com timestamp</p>
                </div>
              </div>
            </div>
          </div>

          {/* Preview Visual da Arquitetura & Sala Ao Vivo */}
          <div className="lg:col-span-5">
            <div className="bg-white rounded-3xl border border-[#c4c7c5] shadow-md overflow-hidden">
              <div className="bg-[#0b57d0] px-5 py-3.5 text-white flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Sparkles className="w-4 h-4" />
                  <span className="text-xs font-semibold tracking-wide uppercase">
                    Sala de Inteligência em Tempo Real
                  </span>
                </div>
                <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-white/20">
                  AMBIENTE ISOLADO LGPD
                </span>
              </div>

              <div className="p-5 space-y-4">
                {/* Simulação de Transcrição + Insight */}
                <div className="p-3.5 rounded-2xl bg-[#f8fafd] border border-[#e0e3e7] space-y-1.5">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="font-semibold text-[#0b57d0]">Entrevistador Técnico • 13:14</span>
                    <span className="px-2 py-0.5 rounded-full bg-[#e6f4ea] text-[#146c2e] font-medium">
                      Voz Capturada ao Vivo
                    </span>
                  </div>
                  <p className="text-xs text-[#1f1f1f] leading-relaxed">
                    "Como você garante resiliência e isolamento de dados em uma arquitetura de
                    microsserviços Java 21 com mensageria?"
                  </p>
                </div>

                <div className="p-3.5 rounded-2xl bg-[#e8f0fe]/60 border border-[#d3e3fd] space-y-2">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="font-semibold text-[#041e49] flex items-center gap-1.5">
                      <Cpu className="w-3.5 h-3.5 text-[#0b57d0]" />
                      IA Analisando Instantaneamente (RAG + Perfil Técnico)
                    </span>
                    <span className="text-[10px] font-mono text-[#0b57d0]">0ms Egress</span>
                  </div>
                  <p className="text-xs text-[#041e49] leading-relaxed">
                    <strong>Insight Detectado:</strong> Requisito crítico de arquitetura distribuída
                    (Resilience4j Circuit Breaker + Outbox Pattern com RabbitMQ e PostgreSQL).
                  </p>
                  <div className="flex flex-wrap gap-1.5 pt-1">
                    <span className="px-2 py-0.5 rounded-md bg-white text-[#0b57d0] text-[10px] font-medium border border-[#d3e3fd]">
                      Ata PDF/DOCX Pronta
                    </span>
                    <span className="px-2 py-0.5 rounded-md bg-white text-[#146c2e] text-[10px] font-medium border border-[#c4eed0]">
                      Dados 100% no Banco Local
                    </span>
                  </div>
                </div>

                <div className="pt-2 border-t border-[#e0e3e7] flex items-center justify-between text-xs text-[#444746]">
                  <span>Sincronização nativa com Google Calendar API v3</span>
                  <span className="font-semibold text-[#0b57d0]">Zero Bots Intrusivos</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* SEÇÃO 1: POR QUE OS CONCORRENTES TRADICIONAIS SÃO UM PROBLEMA PARA EMPRESAS? */}
      <section id="diferenciais" className="py-16 px-6 lg:px-12 bg-white border-y border-[#e0e3e7]">
        <div className="max-w-7xl mx-auto space-y-12">
          <div className="text-center max-w-3xl mx-auto space-y-3">
            <span className="text-xs font-semibold uppercase tracking-wider text-[#0b57d0]">
              O Problema das Ferramentas Genéricas
            </span>
            <h2 className="text-2xl sm:text-3xl font-semibold text-[#1f1f1f]">
              Por que empresas sérias estão abandonando gravadores de reunião em nuvem pública?
            </h2>
            <p className="text-sm text-[#444746]">
              Ferramentas como Read.ai, Fireflies e Otter.ai foram criadas para uso individual, mas
              geram três graves problemas jurídicos, operacionais e financeiros para organizações no Brasil.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div className="md-card p-6 space-y-3 border-t-4 border-t-[#b3261e]">
              <div className="w-10 h-10 rounded-xl bg-[#f9dedc] text-[#b3261e] flex items-center justify-center">
                <Scale className="w-5 h-5" />
              </div>
              <h3 className="text-base font-semibold text-[#1f1f1f]">
                1. Risco Grave de Compliance e LGPD
              </h3>
              <p className="text-xs text-[#444746] leading-relaxed">
                Ao usar gravadores estrangeiros comuns, o áudio de reuniões estratégicas, contratos
                jurídicos, dados fiscais e entrevistas de candidatos é enviado para servidores nos EUA
                para treinar modelos de terceiros — violando políticas de segurança e o Art. 33 da LGPD.
              </p>
              <div className="pt-2 text-xs font-semibold text-[#146c2e] flex items-center gap-1.5">
                <CheckCircle2 className="w-4 h-4 shrink-0" />
                <span>No Meet Intelligence: Banco e S3 100% na sua máquina ou nuvem privada.</span>
              </div>
            </div>

            <div className="md-card p-6 space-y-3 border-t-4 border-t-[#0b57d0]">
              <div className="w-10 h-10 rounded-xl bg-[#e8f0fe] text-[#0b57d0] flex items-center justify-center">
                <Users className="w-5 h-5" />
              </div>
              <h3 className="text-base font-semibold text-[#1f1f1f]">
                2. "Bots Espiões" Inconvenientes na Sala
              </h3>
              <p className="text-xs text-[#444746] leading-relaxed">
                Ninguém gosta quando um robô estranho (<em>"Fireflies.ai Notetaker"</em>) invade uma
                entrevista ou reunião com cliente, constrangendo os participantes e enviando spam
                de resumo para todos os e-mails da chamada sem autorização.
              </p>
              <div className="pt-2 text-xs font-semibold text-[#146c2e] flex items-center gap-1.5">
                <CheckCircle2 className="w-4 h-4 shrink-0" />
                <span>No Meet Intelligence: Captura limpa via navegador, sem bot intruso na chamada.</span>
              </div>
            </div>

            <div className="md-card p-6 space-y-3 border-t-4 border-t-[#146c2e]">
              <div className="w-10 h-10 rounded-xl bg-[#e6f4ea] text-[#146c2e] flex items-center justify-center">
                <KeyRound className="w-5 h-5" />
              </div>
              <h3 className="text-base font-semibold text-[#1f1f1f]">
                3. Cobrança Abusiva por Assento em Dólar
              </h3>
              <p className="text-xs text-[#444746] leading-relaxed">
                Pagar US$ 29 a US$ 39 por mês para cada colaborador apenas para chamar uma API de IA
                que custa centavos torna a operação inviável quando a equipe cresce para 10, 30 ou
                100 pessoas.
              </p>
              <div className="pt-2 text-xs font-semibold text-[#146c2e] flex items-center gap-1.5">
                <CheckCircle2 className="w-4 h-4 shrink-0" />
                <span>No Meet Intelligence: Use sua própria API Key (BYOK) ou IA Local a custo zero por token.</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* SEÇÃO 2: TABELA COMPARATIVA DIRETA */}
      <section id="comparativo" className="py-16 px-6 lg:px-12 max-w-7xl mx-auto space-y-10">
        <div className="text-center max-w-3xl mx-auto space-y-3">
          <span className="text-xs font-semibold uppercase tracking-wider text-[#0b57d0]">
            Comparativo Lado a Lado
          </span>
          <h2 className="text-2xl sm:text-3xl font-semibold text-[#1f1f1f]">
            Por que o Meet Intelligence supera as alternativas do mercado?
          </h2>
          <p className="text-sm text-[#444746]">
            Compare a soberania de dados, o custo e a profundidade técnica da nossa arquitetura frente
            aos concorrentes internacionais.
          </p>
        </div>

        <div className="md-card overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="border-b border-[#e0e3e7] bg-[#f8fafd] text-xs font-semibold text-[#444746]">
                <th className="p-4 min-w-[240px]">Critério Técnico & Comercial</th>
                <th className="p-4 bg-[#e8f0fe] text-[#041e49] border-x border-[#d3e3fd] min-w-[240px]">
                  <div className="flex items-center gap-2">
                    <Video className="w-4 h-4 text-[#0b57d0]" />
                    <span>Meet Intelligence (Este Sistema)</span>
                  </div>
                </th>
                <th className="p-4 min-w-[200px]">Read.ai / Fireflies / Otter</th>
                <th className="p-4 min-w-[200px]">Gemini no Workspace</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#e0e3e7] text-xs">
              <tr>
                <td className="p-4 font-medium text-[#1f1f1f]">
                  Execução com IA 100% Local (Sem Internet)
                </td>
                <td className="p-4 bg-[#e8f0fe]/35 border-x border-[#d3e3fd] font-semibold text-[#146c2e]">
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4" /> Sim (Ollama / Whisper / pgvector local)
                  </span>
                </td>
                <td className="p-4 text-[#b3261e]">
                  <span className="inline-flex items-center gap-1.5">
                    <XCircle className="w-4 h-4" /> Não (100% nuvem externa)
                  </span>
                </td>
                <td className="p-4 text-[#b3261e]">
                  <span className="inline-flex items-center gap-1.5">
                    <XCircle className="w-4 h-4" /> Não (Nuvem Google)
                  </span>
                </td>
              </tr>

              <tr>
                <td className="p-4 font-medium text-[#1f1f1f]">
                  Escolha Livre do Motor de IA (Multi-LLM BYOK)
                </td>
                <td className="p-4 bg-[#e8f0fe]/35 border-x border-[#d3e3fd] font-semibold text-[#146c2e]">
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4" /> Gemini, OpenAI GPT, Claude ou Local
                  </span>
                </td>
                <td className="p-4 text-[#444746]">Fechado / Sem controle do cliente</td>
                <td className="p-4 text-[#444746]">Apenas Google Gemini</td>
              </tr>

              <tr>
                <td className="p-4 font-medium text-[#1f1f1f]">
                  Conformidade LGPD & Sigilo Profissional (Advocacia/RH/Fiscal)
                </td>
                <td className="p-4 bg-[#e8f0fe]/35 border-x border-[#d3e3fd] font-semibold text-[#146c2e]">
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4" /> Soberania Total (Banco no Cliente)
                  </span>
                </td>
                <td className="p-4 text-[#b3261e]">Dados armazenados fora do país</td>
                <td className="p-4 text-[#444746]">Depende de contrato Enterprise caro</td>
              </tr>

              <tr>
                <td className="p-4 font-medium text-[#1f1f1f]">
                  Captura Discreta (Sem Bot Intruso na Chamada)
                </td>
                <td className="p-4 bg-[#e8f0fe]/35 border-x border-[#d3e3fd] font-semibold text-[#146c2e]">
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4" /> Sim (Captura direta no navegador)
                  </span>
                </td>
                <td className="p-4 text-[#b3261e]">
                  <span className="inline-flex items-center gap-1.5">
                    <XCircle className="w-4 h-4" /> Entra como participante robô na sala
                  </span>
                </td>
                <td className="p-4 text-[#146c2e]">Sim (Nativo)</td>
              </tr>

              <tr>
                <td className="p-4 font-medium text-[#1f1f1f]">
                  Chat RAG Auditável com Citação de Timestamp
                </td>
                <td className="p-4 bg-[#e8f0fe]/35 border-x border-[#d3e3fd] font-semibold text-[#146c2e]">
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4" /> Busca vetorial HNSW + salto para o minuto exato
                  </span>
                </td>
                <td className="p-4 text-[#444746]">Limitado aos planos mais caros</td>
                <td className="p-4 text-[#b3261e]">Não possui busca vetorial com timestamp</td>
              </tr>

              <tr>
                <td className="p-4 font-medium text-[#1f1f1f]">
                  Perfis de Análise Especializados por Área
                </td>
                <td className="p-4 bg-[#e8f0fe]/35 border-x border-[#d3e3fd] font-semibold text-[#146c2e]">
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4" /> Prompts customizáveis por tipo de reunião
                  </span>
                </td>
                <td className="p-4 text-[#444746]">Templates genéricos em inglês traduzidos</td>
                <td className="p-4 text-[#b3261e]">Resumo único padronizado</td>
              </tr>

              <tr>
                <td className="p-4 font-medium text-[#1f1f1f]">
                  Exportação Oficial de Atas (PDF, Word DOCX e TXT)
                </td>
                <td className="p-4 bg-[#e8f0fe]/35 border-x border-[#d3e3fd] font-semibold text-[#146c2e]">
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4" /> Nativo com layout corporativo pronto
                  </span>
                </td>
                <td className="p-4 text-[#444746]">Apenas planos pagos Business</td>
                <td className="p-4 text-[#444746]">Apenas Google Docs básico</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      {/* SEÇÃO 3: DESTAQUE IA LOCAL & PRIVACIDADE LGPD */}
      <section id="ia-local" className="py-16 px-6 lg:px-12 bg-[#041e49] text-white">
        <div className="max-w-7xl mx-auto grid grid-cols-1 lg:grid-cols-12 gap-10 items-center">
          <div className="lg:col-span-6 space-y-5">
            <div className="inline-flex items-center gap-2 px-3.5 py-1 rounded-full bg-[#0b57d0] text-[#d3e3fd] text-xs font-semibold">
              <Server className="w-3.5 h-3.5" />
              <span>Arquitetura Híbrida: Nuvem BYOK ou 100% On-Premise</span>
            </div>

            <h2 className="text-2xl sm:text-4xl font-semibold leading-tight">
              Quer sigilo absoluto? Rode com IA Local dentro da máquina ou servidor da sua empresa.
            </h2>

            <p className="text-sm text-[#d3e3fd] leading-relaxed">
              O <strong className="text-white">Meet Intelligence</strong> foi projetado com uma camada
              de orquestração desacoplada (<code className="text-white">AIOrchestrator</code>). Isso
              significa que sua empresa pode escolher entre conectar chaves corporativas (Gemini,
              OpenAI, Claude) ou rodar modelos locais como{' '}
              <strong className="text-white">Qwen 2.5, Llama 3.3, Gemma 3 e Faster-Whisper</strong>{' '}
              dentro da própria rede interna.
            </p>

            <ul className="space-y-3 text-xs text-[#e8f0fe]">
              <li className="flex items-start gap-2.5">
                <CheckCircle2 className="w-4 h-4 text-[#81c995] shrink-0 mt-0.5" />
                <span>
                  <strong>Zero Transferência Internacional de Dados (Art. 33 LGPD):</strong> Áudios,
                  transcrições, embeddings vetoriais e atas permanecem no PostgreSQL e MinIO locais da
                  organização.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <CheckCircle2 className="w-4 h-4 text-[#81c995] shrink-0 mt-0.5" />
                <span>
                  <strong>Custo Zero de API por Reunião no Modo Local:</strong> Com um único servidor
                  local equipado com GPU na rede interna, todos os colaboradores transcrevem e geram
                  atas ilimitadas sem fatura mensal de tokens.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <CheckCircle2 className="w-4 h-4 text-[#81c995] shrink-0 mt-0.5" />
                <span>
                  <strong>Exclusão Definitiva Auditável (Direito ao Esquecimento):</strong> Com 1
                  clique, a reunião, o áudio no S3, as falas e os vetores são expurgados em cascata.
                </span>
              </li>
            </ul>
          </div>

          <div className="lg:col-span-6 grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="p-5 rounded-2xl bg-white/10 border border-white/15 space-y-2.5">
              <Cpu className="w-6 h-6 text-[#a8c7fa]" />
              <h3 className="text-sm font-semibold text-white">Modo 1: 100% IA Local (Air-Gapped)</h3>
              <p className="text-xs text-[#d3e3fd] leading-relaxed">
                Ideal para escritórios jurídicos, consultorias tributárias, saúde e defesa. Roda em
                servidor local com Ollama + Faster-Whisper sem depender de internet externa.
              </p>
            </div>

            <div className="p-5 rounded-2xl bg-white/10 border border-white/15 space-y-2.5">
              <KeyRound className="w-6 h-6 text-[#81c995]" />
              <h3 className="text-sm font-semibold text-white">Modo 2: BYOK (Sua Chave de API)</h3>
              <p className="text-xs text-[#d3e3fd] leading-relaxed">
                Conecte a chave corporativa da sua empresa no Google AI Studio (Gemini), OpenAI ou
                Anthropic Claude e pague apenas centavos diretamente ao provedor.
              </p>
            </div>

            <div className="p-5 rounded-2xl bg-white/10 border border-white/15 space-y-2.5">
              <Database className="w-6 h-6 text-[#fde293]" />
              <h3 className="text-sm font-semibold text-white">Busca Vetorial pgvector Nativa</h3>
              <p className="text-xs text-[#d3e3fd] leading-relaxed">
                Pergunte qualquer coisa sobre uma reunião de 2 horas e receba a resposta exata com o
                minuto e segundo em que o participante falou aquilo.
              </p>
            </div>

            <div className="p-5 rounded-2xl bg-white/10 border border-white/15 space-y-2.5">
              <FileText className="w-6 h-6 text-[#f2b8b5]" />
              <h3 className="text-sm font-semibold text-white">Atas Formais em 1 Clique</h3>
              <p className="text-xs text-[#d3e3fd] leading-relaxed">
                Exportação imediata de atas executivas com participantes, resumo, deliberações e
                plano de ação em PDF, Word (DOCX) e TXT.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* SEÇÃO 4: CASOS DE USO POR NICHO */}
      <section id="nichos" className="py-16 px-6 lg:px-12 max-w-7xl mx-auto space-y-10">
        <div className="text-center max-w-3xl mx-auto space-y-3">
          <span className="text-xs font-semibold uppercase tracking-wider text-[#0b57d0]">
            Especialização Vertical
          </span>
          <h2 className="text-2xl sm:text-3xl font-semibold text-[#1f1f1f]">
            Projetado para quem precisa de documentos técnicos perfeitos, não apenas "resuminhos"
          </h2>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="md-card p-6 space-y-4">
            <div className="w-11 h-11 rounded-2xl bg-[#e8f0fe] text-[#0b57d0] flex items-center justify-center">
              <Code2 className="w-5 h-5" />
            </div>
            <h3 className="text-base font-semibold text-[#1f1f1f]">
              Recrutamento Tech & Entrevistas de Engenharia
            </h3>
            <p className="text-xs text-[#444746] leading-relaxed">
              Sincronize suas entrevistas técnicas do Google Calendar e deixe a IA avaliar
              automaticamente o domínio técnico do candidato (ex.: Java, Arquitetura, Bancos de
              Dados), gerando um parecer completo com pontos fortes e pontos de atenção em segundos.
            </p>
          </div>

          <div className="md-card p-6 space-y-4">
            <div className="w-11 h-11 rounded-2xl bg-[#e6f4ea] text-[#146c2e] flex items-center justify-center">
              <Scale className="w-5 h-5" />
            </div>
            <h3 className="text-base font-semibold text-[#1f1f1f]">
              Consultorias Tributárias, Contábeis & Jurídicas
            </h3>
            <p className="text-xs text-[#444746] leading-relaxed">
              Reuniões sobre planejamento tributário, impostos e contratos exigem sigilo absoluto.
              Gere atas formais em Word (DOCX) e PDF prontas para enviar ao cliente, com todas as
              decisões e responsáveis documentados sob proteção da LGPD.
            </p>
          </div>

          <div className="md-card p-6 space-y-4">
            <div className="w-11 h-11 rounded-2xl bg-[#fef7e0] text-[#b06000] flex items-center justify-center">
              <Briefcase className="w-5 h-5" />
            </div>
            <h3 className="text-base font-semibold text-[#1f1f1f]">
              Diretoria, Squads de Produto & Vendas B2B
            </h3>
            <p className="text-xs text-[#444746] leading-relaxed">
              Nunca mais perca um prazo prometido em reunião. O motor de IA identifica automaticamente
              cada tarefa (Action Item), atribui ao responsável mencionado na fala e consolida as
              decisões arquiteturais ou comerciais.
            </p>
          </div>
        </div>
      </section>

      {/* SEÇÃO 5: CALCULADORA INTERATIVA DE ECONOMIA */}
      <section id="economia" className="py-16 px-6 lg:px-12 bg-white border-t border-[#e0e3e7]">
        <div className="max-w-4xl mx-auto md-card p-8 bg-[#f8fafd] space-y-6">
          <div className="text-center space-y-2">
            <span className="text-xs font-semibold uppercase tracking-wider text-[#0b57d0]">
              Simulador Financeiro B2B
            </span>
            <h2 className="text-2xl font-semibold text-[#1f1f1f]">
              Quanto sua empresa economiza trocando SaaS em dólar pelo Meet Intelligence?
            </h2>
            <p className="text-xs text-[#444746]">
              Arraste o controle abaixo para simular o tamanho da sua equipe:
            </p>
          </div>

          <div className="space-y-3 pt-2">
            <div className="flex items-center justify-between text-sm font-semibold">
              <span>Tamanho da Equipe / Participantes:</span>
              <span className="px-3.5 py-1 rounded-full bg-[#0b57d0] text-white">
                {teamSize} profissionais
              </span>
            </div>
            <input
              type="range"
              min={3}
              max={100}
              value={teamSize}
              onChange={(e) => setTeamSize(Number(e.target.value))}
              className="w-full accent-[#0b57d0] cursor-pointer"
            />
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-5 pt-2">
            <div className="p-5 rounded-2xl bg-white border border-[#f2b8b5] space-y-1.5">
              <span className="text-xs font-semibold text-[#b3261e]">
                Concorrentes (Read.ai / Fireflies / Otter Business)
              </span>
              <p className="text-2xl font-semibold text-[#1f1f1f]">
                R$ {competitorMonthlyBrl.toLocaleString('pt-BR')} <span className="text-xs font-normal">/ mês</span>
              </p>
              <p className="text-xs text-[#444746]">
                Cerca de <strong>R$ {competitorYearlyBrl.toLocaleString('pt-BR')} por ano</strong> +
                dados armazenados fora do Brasil.
              </p>
            </div>

            <div className="p-5 rounded-2xl bg-[#e6f4ea] border border-[#c4eed0] space-y-1.5">
              <span className="text-xs font-semibold text-[#146c2e]">
                Meet Intelligence (Modo IA Local ou BYOK)
              </span>
              <p className="text-2xl font-semibold text-[#146c2e]">
                R$ 0 por assento adicional
              </p>
              <p className="text-xs text-[#041e49]">
                Usuários ilimitados na sua organização, pagando apenas sua própria chave de API (ou
                zero com IA Local) e <strong>100% em conformidade com a LGPD</strong>.
              </p>
            </div>
          </div>

          <div className="pt-4 text-center">
            <button
              onClick={handlePrimaryAction}
              className="px-8 py-3.5 rounded-full bg-[#0b57d0] hover:bg-[#0842a0] text-white font-semibold text-sm inline-flex items-center gap-2 shadow-sm transition-all cursor-pointer"
            >
              <span>{isAuthenticated ? 'Voltar para o Painel Principal' : 'Acessar o Meet Intelligence Agora'}</span>
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      </section>

      {/* FOOTER */}
      <footer className="py-8 px-6 lg:px-12 bg-[#f8fafd] border-t border-[#e0e3e7] text-center text-xs text-[#444746]">
        <p>
          <strong>Meet Intelligence SaaS & On-Premise</strong> — Desenvolvido com Java 21 LTS, Spring
          Boot 3.3, React 19, PostgreSQL pgvector e Google Material Design 3.
        </p>
      </footer>
    </div>
  );
};
