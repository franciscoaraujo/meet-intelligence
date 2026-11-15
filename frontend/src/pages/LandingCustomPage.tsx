import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import {
  Video,
  ShieldCheck,
  Server,
  Cpu,
  CheckCircle2,
  XCircle,
  ArrowRight,
  Sparkles,
  FileText,
  Building2,
  Database,
  Layers,
  Wrench,
  Scale,
  Code2,
  Stethoscope,
  Users,
  LayoutDashboard,
  Workflow,
  Palette,
  Lock,
  Clock,
  Check,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';

type VerticalKey = 'tributario' | 'recrutamento' | 'software' | 'saude';

interface VerticalCase {
  title: string;
  badge: string;
  pain: string;
  meetingExample: string;
  customPromptFocus: string[];
  deliverableTitle: string;
  deliverablePreview: string[];
  integrationTarget: string;
}

const VERTICAL_CASES: Record<VerticalKey, VerticalCase> = {
  tributario: {
    title: 'Consultorias Tributárias, Contábeis & Escritórios Jurídicos',
    badge: 'Sigilo Absoluto LGPD + Papel Timbrado Oficial',
    pain: 'Sócios e consultores seniores perdem horas após reuniões estratégicas redigindo memórias de cálculo, pareceres preliminares e cobrando documentos prometidos pelo cliente.',
    meetingExample:
      'Reunião de Planejamento Tributário e Revisão de Impostos (ICMS / PIS / COFINS / Reforma Tributária) com Diretoria Financeira do Cliente.',
    customPromptFocus: [
      'Identificação automática do Regime Tributário (Lucro Real / Presumido) e teses discutidas',
      'Mapeamento de passivos fiscais, contingências e prazos legais mencionados',
      'Extração de checklist de documentos contábeis/fiscais que o cliente ficou de enviar',
    ],
    deliverableTitle: 'Memorando Executivo & Ata Jurídico-Tributária (DOCX / PDF no Timbre do Escritório)',
    deliverablePreview: [
      '1. RESUMO DO CASO & ENQUADRAMENTO TRIBUTÁRIO DISCUTIDO',
      '2. PONTOS DE RISCO E PASSIVOS IDENTIFICADOS NA FALA DO CLIENTE (Com citação do minuto exato)',
      '3. DELIBERAÇÕES E ESTRATÉGIA JURÍDICA APROVADA',
      '4. CHECKLIST DE DOCUMENTOS PENDENTES (Responsável + Prazo)',
    ],
    integrationTarget: 'Exportação DOCX no modelo oficial do escritório + GED / Google Drive Privado',
  },
  recrutamento: {
    title: 'Consultorias de RH, Executive Search & Tech Recruiting',
    badge: 'Scorecard Técnico Automático + Integração ATS',
    pain: 'Tech Recruiters e Tech Leads fazem dezenas de entrevistas por semana no Google Meet e gastam 30 a 45 minutos por candidato preenchendo pareceres técnicos para enviar ao cliente ou gestor.',
    meetingExample:
      'Avaliação Técnica | Desenvolvedor(a) Back-end Sênior (Java 21, Spring Boot, Microsserviços e Impostos).',
    customPromptFocus: [
      'Avaliação objetiva de profundidade técnica em cada requisito da vaga (Java, Mensageria, SQL, Cloud)',
      'Detecção de senioridade real demonstrada nas respostas vs. currículo',
      'Mapeamento automático de pretensão salarial, modelo de trabalho e disponibilidade de início',
    ],
    deliverableTitle: 'Parecer Técnico Estruturado do Candidato (Scorecard 0 a 10 + Recomendação)',
    deliverablePreview: [
      '• Domínio em Java 21 & Spring Boot: 9.5/10 (Explicou concorrência, Virtual Threads e transacionalidade no min 14:20)',
      '• Arquitetura & Mensageria (RabbitMQ/Kafka): 9.0/10 (Detalhou padrão Outbox e idempotência no min 22:10)',
      '• Pontos Fortes & Red Flags identificados na entrevista',
      '• Veredito da IA: Altamente Recomendado para a etapa final',
    ],
    integrationTarget: 'Envio automático via Webhook para Gupy, Greenhouse, Pipefy, Notion ou PDF para o Cliente',
  },
  software: {
    title: 'Fábricas de Software, Consultorias de TI & Squads de Produto',
    badge: 'De Reunião com Cliente direto para Épicos & User Stories no Jira',
    pain: 'Reuniões de levantamento de requisitos (Discovery) com clientes geram anotações soltas; requisitos importantes são esquecidos e o time de engenharia constrói diferente do que foi combinado.',
    meetingExample:
      'Reunião de Alinhamento de Arquitetura e Levantamento de Escopo com Product Owner e Cliente.',
    customPromptFocus: [
      'Conversão automática das dores relatadas pelo cliente em Requisitos Funcionais e Não-Funcionais',
      'Geração de User Stories no formato padrão (Como... Eu quero... Para que...) com Critérios de Aceite (Gherkin)',
      'Registro auditável de mudanças de escopo aprovadas em reunião para evitar disputas comerciais',
    ],
    deliverableTitle: 'Documento de Especificação Técnica + Backlog de User Stories Pronto',
    deliverablePreview: [
      '[US-01] Sincronização Automática de Eventos Futuros do Google Calendar API v3',
      'Critérios de Aceite: Dado um usuário autenticado via OAuth2, quando abrir a agenda, exibir apenas compromissos futuros...',
      '[DECISÃO ARQUITETURAL - Min 31:05]: Cliente aprovou uso de PostgreSQL pgvector em vez de banco vetorial pago externo.',
      '[RISCO DE PRAZO]: Dependência de liberação de credenciais pelo time de segurança do cliente até sexta-feira.',
    ],
    integrationTarget: 'Criação automática de Issues no Jira, Linear, Azure DevOps ou GitHub Projects',
  },
  saude: {
    title: 'Clínicas Médicas, Telemedicina, Psicologia & Saúde Corporativa',
    badge: '100% IA Local Air-Gapped (Sigilo Médico + LGPD)',
    pain: 'Profissionais de saúde atendendo por teleconsulta no Google Meet precisam olhar para o paciente e digitar o prontuário ao mesmo tempo, prejudicando a atenção clínica.',
    meetingExample: 'Teleconsulta de Acompanhamento Clínico / Anamnese via Google Meet.',
    customPromptFocus: [
      'Processamento 100% em servidor local da clínica (nenhum áudio de paciente vai para nuvens externas)',
      'Estruturação automática da conversa no padrão médico SOAP (Subjetivo, Objetivo, Avaliação e Conduta)',
      'Separação clara entre queixa principal, histórico, medicações em uso e orientações prescritas',
    ],
    deliverableTitle: 'Evolução Clínica Estruturada (Padrão SOAP) Pronta para o Prontuário Eletrônico',
    deliverablePreview: [
      'S (Subjetivo): Paciente relata melhora após ajuste posológico há 14 dias...',
      'O (Objetivo): Exames laboratoriais apresentados durante a chamada dentro da normalidade...',
      'A (Avaliação): Quadro estável com boa resposta terapêutica...',
      'P (Plano/Conduta): Manter conduta atual e retorno agendado em 60 dias.',
    ],
    integrationTarget: 'Cópia em 1 clique ou integração via API local com o sistema de Prontuário Eletrônico (PEP)',
  },
};

export const LandingCustomPage: React.FC = () => {
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();
  const [selectedVertical, setSelectedVertical] = useState<VerticalKey>('tributario');

  const activeCase = VERTICAL_CASES[selectedVertical];

  return (
    <div className="min-h-screen bg-[#f8fafd] text-[#1f1f1f] overflow-x-hidden selection:bg-[#d3e3fd] selection:text-[#041e49]">
      {/* Barra Superior de Alternância entre as 2 Propostas de Landing Page */}
      <div className="bg-[#041e49] text-white px-6 py-2 text-xs flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="px-2 py-0.5 rounded bg-[#0b57d0] text-[#d3e3fd] font-semibold text-[10px] uppercase">
            Modo de Avaliação Comercial
          </span>
          <span className="text-[#d3e3fd]">
            Compare os dois posicionamentos de venda do produto:
          </span>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to="/landing"
            className="px-3 py-1 rounded-full bg-white/10 hover:bg-white/20 text-[#d3e3fd] transition-colors"
          >
            Versão 1: Produto SaaS vs. Concorrentes (/landing)
          </Link>
          <Link
            to="/landing_2"
            className="px-3 py-1 rounded-full bg-[#c2e7ff] text-[#001d35] font-semibold"
          >
            Versão 2: Solução Sob Demanda & White-Label (/landing_2)
          </Link>
        </div>
      </div>

      {/* Top Navbar */}
      <header className="sticky top-0 z-50 h-16 px-6 lg:px-12 bg-white/95 backdrop-blur-md border-b border-[#e0e3e7] flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-[#0b57d0] flex items-center justify-center text-white shadow-xs">
            <Video className="w-5 h-5" />
          </div>
          <div>
            <span className="font-semibold text-[17px] tracking-tight text-[#1f1f1f] block leading-tight">
              Meet Intelligence <span className="text-[#0b57d0]">Custom B2B</span>
            </span>
            <span className="text-[11px] text-[#444746] font-medium block">
              Engenharia de IA Sob Demanda & White-Label
            </span>
          </div>
        </div>

        <nav className="hidden md:flex items-center gap-7 text-xs font-medium text-[#444746]">
          <a href="#simulador-setor" className="hover:text-[#0b57d0] transition-colors">
            Simular para meu Setor
          </a>
          <a href="#como-funciona" className="hover:text-[#0b57d0] transition-colors">
            Arquitetura 85/15
          </a>
          <a href="#comparativo-sob-medida" className="hover:text-[#0b57d0] transition-colors">
            Prateleira vs. Sob Medida
          </a>
          <a href="#formatos-implantacao" className="hover:text-[#0b57d0] transition-colors">
            Modelos de Implantação
          </a>
        </nav>

        <div className="flex items-center gap-3">
          {isAuthenticated ? (
            <button onClick={() => navigate('/')} className="md-btn-filled !py-2 !px-5 !text-xs">
              <LayoutDashboard className="w-3.5 h-3.5" />
              <span>Voltar ao Sistema</span>
            </button>
          ) : (
            <button
              onClick={() => navigate('/login')}
              className="md-btn-filled !py-2 !px-5 !text-xs"
            >
              <span>Acessar Demonstração Funcional</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </header>

      {/* HERO SECTION: FOCO EM SOLUÇÃO SOB MEDIDA / WHITE-LABEL */}
      <section className="pt-14 pb-16 px-6 lg:px-12 max-w-7xl mx-auto">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          <div className="lg:col-span-7 space-y-6">
            <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#e8f0fe] border border-[#d3e3fd] text-[#0b57d0] text-xs font-semibold">
              <Wrench className="w-4 h-4" />
              <span>Implantação Personalizada • Sua Marca • Seus Modelos de Documento • Seu Servidor</span>
            </div>

            <h1 className="text-3xl sm:text-5xl font-semibold tracking-tight text-[#1f1f1f] leading-[1.14]">
              Não adapte sua operação a um SaaS genérico. Tenha uma{' '}
              <span className="text-[#0b57d0]">IA de Reuniões feita sob medida para o seu negócio.</span>
            </h1>

            <p className="text-base sm:text-lg text-[#444746] leading-relaxed max-w-2xl">
              Gravadores de prateleira entregam resumos genéricos que sua equipe ainda precisa
              reescrever manualmente. Nós implantamos uma{' '}
              <strong className="text-[#1f1f1f]">infraestrutura proprietária de IA para Google Meet</strong>{' '}
              no seu ambiente (Nuvem Privada ou <strong>100% IA Local na sua máquina</strong>),
              calibrada para gerar automaticamente <strong className="text-[#1f1f1f]">o documento, parecer ou integração exata</strong> que a sua empresa utiliza.
            </p>

            <div className="flex flex-wrap items-center gap-3.5 pt-2">
              <a
                href="#simulador-setor"
                className="px-7 py-3.5 rounded-full bg-[#0b57d0] hover:bg-[#0842a0] text-white font-semibold text-sm flex items-center gap-2.5 shadow-sm transition-all"
              >
                <span>Ver o que a IA gera no seu Setor</span>
                <ArrowRight className="w-4 h-4" />
              </a>

              <button
                onClick={() => navigate(isAuthenticated ? '/ai-profiles' : '/login')}
                className="px-6 py-3.5 rounded-full bg-white hover:bg-[#edf2fc] text-[#1f1f1f] border border-[#c4c7c5] font-medium text-sm transition-colors cursor-pointer"
              >
                Ver Perfis Customizáveis na Prática
              </button>
            </div>

            {/* 3 Pilares da Venda Sob Demanda */}
            <div className="pt-4 grid grid-cols-1 sm:grid-cols-3 gap-4 border-t border-[#e0e3e7]">
              <div className="flex items-start gap-2.5">
                <Palette className="w-5 h-5 text-[#0b57d0] shrink-0 mt-0.5" />
                <div>
                  <p className="text-xs font-semibold text-[#1f1f1f]">100% White-Label</p>
                  <p className="text-[11px] text-[#444746]">
                    Com a logo, domínio e papel timbrado DOCX/PDF da sua empresa.
                  </p>
                </div>
              </div>

              <div className="flex items-start gap-2.5">
                <Server className="w-5 h-5 text-[#146c2e] shrink-0 mt-0.5" />
                <div>
                  <p className="text-xs font-semibold text-[#1f1f1f]">IA Local ou Chave Própria</p>
                  <p className="text-[11px] text-[#444746]">
                    Rode com Ollama na sua máquina (LGPD) ou use sua chave Gemini/Grok.
                  </p>
                </div>
              </div>

              <div className="flex items-start gap-2.5">
                <Workflow className="w-5 h-5 text-[#b06000] shrink-0 mt-0.5" />
                <div>
                  <p className="text-xs font-semibold text-[#1f1f1f]">Integrado ao seu Fluxo</p>
                  <p className="text-[11px] text-[#444746]">
                    Conectamos a saída da reunião direto ao seu ERP, CRM, Jira ou ATS.
                  </p>
                </div>
              </div>
            </div>
          </div>

          {/* Card Lateral: A Regra 85% Pronto + 15% Sob Medida */}
          <div className="lg:col-span-5">
            <div className="md-card p-6 bg-white border-[#c4c7c5] shadow-md space-y-5">
              <div className="flex items-center justify-between border-b border-[#e0e3e7] pb-4">
                <div>
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-[#0b57d0]">
                    Por que entregamos em dias, não meses?
                  </span>
                  <h3 className="text-lg font-semibold text-[#1f1f1f] mt-0.5">
                    A Engenharia 85% Pronta + 15% Exclusiva
                  </h3>
                </div>
                <div className="w-10 h-10 rounded-full bg-[#e8f0fe] text-[#0b57d0] flex items-center justify-center font-bold text-xs">
                  7-15d
                </div>
              </div>

              <div className="space-y-3">
                <div className="p-4 rounded-2xl bg-[#f8fafd] border border-[#e0e3e7] space-y-2">
                  <div className="flex items-center justify-between text-xs font-semibold text-[#1f1f1f]">
                    <span className="flex items-center gap-1.5">
                      <CheckCircle2 className="w-4 h-4 text-[#146c2e]" />
                      85% Core Enterprise (Já Construído e Testado)
                    </span>
                    <span className="text-[11px] text-[#146c2e] bg-[#e6f4ea] px-2 py-0.5 rounded-full">
                      Pronto Hoje
                    </span>
                  </div>
                  <p className="text-xs text-[#444746] leading-relaxed">
                    Sincronização Google Calendar API v3, autenticação OAuth 2.0, captura de áudio,
                    transcrição ao vivo, banco vetorial PostgreSQL <code>pgvector</code>, WebSocket
                    em tempo real e orquestrador Multi-LLM em Java 21 + Spring Boot.
                  </p>
                </div>

                <div className="p-4 rounded-2xl bg-[#e8f0fe]/60 border border-[#d3e3fd] space-y-2">
                  <div className="flex items-center justify-between text-xs font-semibold text-[#041e49]">
                    <span className="flex items-center gap-1.5">
                      <Sparkles className="w-4 h-4 text-[#0b57d0]" />
                      15% Customização Sob Demanda (Para Você)
                    </span>
                    <span className="text-[11px] text-[#0b57d0] bg-white px-2 py-0.5 rounded-full border border-[#d3e3fd]">
                      Sob Medida
                    </span>
                  </div>
                  <p className="text-xs text-[#041e49] leading-relaxed">
                    Personalizamos os prompts especialistas para o vocabulário do seu negócio,
                    formatamos o template Word (DOCX)/PDF exatamente no padrão da sua empresa e
                    conectamos via API ao seu sistema interno.
                  </p>
                </div>
              </div>

              <div className="p-3.5 rounded-xl bg-[#e6f4ea] border border-[#c4eed0] text-xs text-[#146c2e] font-medium flex items-center justify-between">
                <span>Economia de até 80% vs. desenvolver do zero</span>
                <span>Sem mensalidade por usuário</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* SEÇÃO INTERATIVA: SIMULADOR DE ENTREGA SOB MEDIDA POR SETOR */}
      <section id="simulador-setor" className="py-16 px-6 lg:px-12 bg-white border-y border-[#e0e3e7]">
        <div className="max-w-7xl mx-auto space-y-8">
          <div className="text-center max-w-3xl mx-auto space-y-3">
            <span className="text-xs font-semibold uppercase tracking-wider text-[#0b57d0]">
              Veja na Prática por Segmento
            </span>
            <h2 className="text-2xl sm:text-3xl font-semibold text-[#1f1f1f]">
              Escolha o seu setor e veja como adaptamos o sistema para a sua necessidade real
            </h2>
            <p className="text-sm text-[#444746]">
              Clique em uma das áreas abaixo para visualizar como transformamos uma chamada do Google
              Meet no entregável final da sua operação:
            </p>
          </div>

          {/* Botões Seletores de Vertical */}
          <div className="flex flex-wrap items-center justify-center gap-3">
            <button
              onClick={() => setSelectedVertical('tributario')}
              className={`px-5 py-2.5 rounded-full text-xs font-semibold flex items-center gap-2 transition-all cursor-pointer border ${
                selectedVertical === 'tributario'
                  ? 'bg-[#0b57d0] text-white border-[#0b57d0] shadow-xs'
                  : 'bg-[#f8fafd] text-[#444746] border-[#c4c7c5] hover:bg-[#edf2fc]'
              }`}
            >
              <Scale className="w-4 h-4" />
              <span>Tributário, Contábil & Jurídico</span>
            </button>

            <button
              onClick={() => setSelectedVertical('recrutamento')}
              className={`px-5 py-2.5 rounded-full text-xs font-semibold flex items-center gap-2 transition-all cursor-pointer border ${
                selectedVertical === 'recrutamento'
                  ? 'bg-[#0b57d0] text-white border-[#0b57d0] shadow-xs'
                  : 'bg-[#f8fafd] text-[#444746] border-[#c4c7c5] hover:bg-[#edf2fc]'
              }`}
            >
              <Users className="w-4 h-4" />
              <span>RH & Tech Recruiting (Entrevistas)</span>
            </button>

            <button
              onClick={() => setSelectedVertical('software')}
              className={`px-5 py-2.5 rounded-full text-xs font-semibold flex items-center gap-2 transition-all cursor-pointer border ${
                selectedVertical === 'software'
                  ? 'bg-[#0b57d0] text-white border-[#0b57d0] shadow-xs'
                  : 'bg-[#f8fafd] text-[#444746] border-[#c4c7c5] hover:bg-[#edf2fc]'
              }`}
            >
              <Code2 className="w-4 h-4" />
              <span>Fábricas de Software & Produto</span>
            </button>

            <button
              onClick={() => setSelectedVertical('saude')}
              className={`px-5 py-2.5 rounded-full text-xs font-semibold flex items-center gap-2 transition-all cursor-pointer border ${
                selectedVertical === 'saude'
                  ? 'bg-[#0b57d0] text-white border-[#0b57d0] shadow-xs'
                  : 'bg-[#f8fafd] text-[#444746] border-[#c4c7c5] hover:bg-[#edf2fc]'
              }`}
            >
              <Stethoscope className="w-4 h-4" />
              <span>Clínicas & Telemedicina (IA Local)</span>
            </button>
          </div>

          {/* Painel Dinâmico do Caso Selecionado */}
          <div className="md-card p-6 lg:p-8 bg-[#f8fafd] border-[#d3e3fd] grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            <div className="lg:col-span-6 space-y-5">
              <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#e8f0fe] text-[#0b57d0] text-xs font-semibold">
                <span>{activeCase.badge}</span>
              </div>

              <h3 className="text-xl font-semibold text-[#1f1f1f]">{activeCase.title}</h3>

              <div className="p-4 rounded-2xl bg-[#f9dedc]/50 border border-[#f2b8b5] space-y-1">
                <span className="text-[11px] font-semibold uppercase text-[#b3261e]">
                  A Dor que Resolvemos na Sua Operação:
                </span>
                <p className="text-xs text-[#1f1f1f] leading-relaxed">{activeCase.pain}</p>
              </div>

              <div className="space-y-2.5">
                <p className="text-xs font-semibold text-[#1f1f1f] uppercase tracking-wider">
                  O que calibramos sob medida na IA para você:
                </p>
                {activeCase.customPromptFocus.map((item, idx) => (
                  <div key={idx} className="flex items-start gap-2.5 text-xs text-[#444746]">
                    <CheckCircle2 className="w-4 h-4 text-[#0b57d0] shrink-0 mt-0.5" />
                    <span>{item}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="lg:col-span-6 bg-white rounded-2xl border border-[#c4c7c5] p-6 space-y-4 shadow-2xs">
              <div className="flex items-center justify-between border-b border-[#e0e3e7] pb-3">
                <div className="flex items-center gap-2">
                  <FileText className="w-4 h-4 text-[#0b57d0]" />
                  <span className="text-xs font-semibold text-[#1f1f1f]">
                    Entregável Gerado Automaticamente após o Meet
                  </span>
                </div>
                <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#e6f4ea] text-[#146c2e]">
                  Pronto em 15 segundos
                </span>
              </div>

              <div className="p-3 rounded-xl bg-[#f8fafd] border border-[#e0e3e7] text-xs">
                <span className="text-[#444746] font-medium">Reunião Capturada: </span>
                <strong className="text-[#1f1f1f]">{activeCase.meetingExample}</strong>
              </div>

              <div className="space-y-2">
                <p className="text-xs font-semibold text-[#0b57d0]">{activeCase.deliverableTitle}</p>
                <div className="p-4 rounded-xl bg-[#f8fafd] border border-[#e0e3e7] space-y-2 font-mono text-[11px] text-[#1f1f1f]">
                  {activeCase.deliverablePreview.map((line, i) => (
                    <p key={i} className="leading-relaxed">
                      {line}
                    </p>
                  ))}
                </div>
              </div>

              <div className="pt-2 flex items-center justify-between text-xs text-[#146c2e] font-medium">
                <span>Destino: {activeCase.integrationTarget}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* SEÇÃO: COMPARATIVO ENTRE AS 3 OPÇÕES DO MERCADO */}
      <section id="comparativo-sob-medida" className="py-16 px-6 lg:px-12 max-w-7xl mx-auto space-y-10">
        <div className="text-center max-w-3xl mx-auto space-y-3">
          <span className="text-xs font-semibold uppercase tracking-wider text-[#0b57d0]">
            Decisão Estratégica de Tecnologia
          </span>
          <h2 className="text-2xl sm:text-3xl font-semibold text-[#1f1f1f]">
            Por que contratar nossa implantação sob demanda em vez de um SaaS genérico ou desenvolvimento do zero?
          </h2>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {/* Coluna 1: SaaS Genérico */}
          <div className="md-card p-6 space-y-4 bg-white opacity-90">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-[#b3261e] uppercase">Opção 1</span>
              <XCircle className="w-5 h-5 text-[#b3261e]" />
            </div>
            <h3 className="text-lg font-semibold text-[#1f1f1f]">
              Alugar SaaS Genérico (Read.ai / Fireflies / Otter)
            </h3>
            <ul className="space-y-2.5 text-xs text-[#444746]">
              <li className="flex items-start gap-2">
                <XCircle className="w-4 h-4 text-[#b3261e] shrink-0 mt-0.5" />
                <span>Resumo genérico que não segue o modelo de documento da sua empresa.</span>
              </li>
              <li className="flex items-start gap-2">
                <XCircle className="w-4 h-4 text-[#b3261e] shrink-0 mt-0.5" />
                <span>Áudios confidenciais enviados para nuvens públicas nos EUA (risco LGPD).</span>
              </li>
              <li className="flex items-start gap-2">
                <XCircle className="w-4 h-4 text-[#b3261e] shrink-0 mt-0.5" />
                <span>Cobrança perpétua em dólar por cada colaborador (US$ 29+/mês por pessoa).</span>
              </li>
              <li className="flex items-start gap-2">
                <XCircle className="w-4 h-4 text-[#b3261e] shrink-0 mt-0.5" />
                <span>Impossível rodar com IA Local na máquina da empresa.</span>
              </li>
            </ul>
          </div>

          {/* Coluna 2: Fábrica de Software do Zero */}
          <div className="md-card p-6 space-y-4 bg-white opacity-90">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-[#b06000] uppercase">Opção 2</span>
              <Clock className="w-5 h-5 text-[#b06000]" />
            </div>
            <h3 className="text-lg font-semibold text-[#1f1f1f]">
              Desenvolver do Zero com Fábrica de Software
            </h3>
            <ul className="space-y-2.5 text-xs text-[#444746]">
              <li className="flex items-start gap-2">
                <XCircle className="w-4 h-4 text-[#b06000] shrink-0 mt-0.5" />
                <span>Custo proibitivo de R$ 100.000 a R$ 250.000 para criar toda a base.</span>
              </li>
              <li className="flex items-start gap-2">
                <XCircle className="w-4 h-4 text-[#b06000] shrink-0 mt-0.5" />
                <span>Prazo de 5 a 8 meses até ter sincronização Google Calendar, STT e RAG estáveis.</span>
              </li>
              <li className="flex items-start gap-2">
                <XCircle className="w-4 h-4 text-[#b06000] shrink-0 mt-0.5" />
                <span>Alto risco técnico em indexação vetorial, diarização e mensageria em tempo real.</span>
              </li>
            </ul>
          </div>

          {/* Coluna 3: Meet Intelligence Sob Demanda */}
          <div className="md-card p-6 space-y-4 bg-[#e8f0fe]/45 border-2 border-[#0b57d0] shadow-md">
            <div className="flex items-center justify-between">
              <span className="px-2.5 py-0.5 rounded-full bg-[#0b57d0] text-white text-[11px] font-semibold uppercase">
                Escolha Inteligente
              </span>
              <CheckCircle2 className="w-5 h-5 text-[#0b57d0]" />
            </div>
            <h3 className="text-lg font-semibold text-[#041e49]">
              Meet Intelligence Sob Demanda (Core Pronto + Customização)
            </h3>
            <ul className="space-y-2.5 text-xs text-[#1f1f1f]">
              <li className="flex items-start gap-2">
                <Check className="w-4 h-4 text-[#146c2e] shrink-0 mt-0.5" />
                <span>
                  <strong>Entrega em 7 a 15 dias:</strong> 85% da engenharia pesada já está pronta e
                  validada.
                </span>
              </li>
              <li className="flex items-start gap-2">
                <Check className="w-4 h-4 text-[#146c2e] shrink-0 mt-0.5" />
                <span>
                  <strong>100% Adaptado ao seu Processo:</strong> Geramos o documento Word/PDF ou
                  integração exata que sua operação precisa.
                </span>
              </li>
              <li className="flex items-start gap-2">
                <Check className="w-4 h-4 text-[#146c2e] shrink-0 mt-0.5" />
                <span>
                  <strong>IA Local na sua Máquina ou BYOK:</strong> Conformidade total com a LGPD,
                  sem mensalidade por assento.
                </span>
              </li>
              <li className="flex items-start gap-2">
                <Check className="w-4 h-4 text-[#146c2e] shrink-0 mt-0.5" />
                <span>
                  <strong>White-Label:</strong> Implantado com o nome e identidade visual da sua
                  empresa.
                </span>
              </li>
            </ul>
          </div>
        </div>
      </section>

      {/* SEÇÃO: OS 3 FORMATOS DE IMPLANTAÇÃO SOB DEMANDA */}
      <section id="formatos-implantacao" className="py-16 px-6 lg:px-12 bg-[#041e49] text-white">
        <div className="max-w-7xl mx-auto space-y-10">
          <div className="text-center max-w-3xl mx-auto space-y-3">
            <span className="text-xs font-semibold uppercase tracking-wider text-[#a8c7fa]">
              Flexibilidade Total de Infraestrutura
            </span>
            <h2 className="text-2xl sm:text-3xl font-semibold">
              Você escolhe onde o sistema e a Inteligência Artificial vão rodar
            </h2>
            <p className="text-xs sm:text-sm text-[#d3e3fd]">
              Adaptamos a arquitetura de acordo com a política de segurança e o orçamento da sua
              organização:
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div className="p-6 rounded-3xl bg-white/10 border border-white/15 space-y-4">
              <div className="w-11 h-11 rounded-2xl bg-[#0b57d0] text-white flex items-center justify-center">
                <Cpu className="w-5 h-5" />
              </div>
              <span className="inline-block px-2.5 py-0.5 rounded-full bg-[#81c995]/20 text-[#81c995] text-[11px] font-semibold">
                Máxima Proteção LGPD • Custo Zero de API
              </span>
              <h3 className="text-lg font-semibold">
                1. Implantação Local (Air-Gapped On-Premise)
              </h3>
              <p className="text-xs text-[#d3e3fd] leading-relaxed">
                Instalamos o sistema completo + motor de IA local (<strong>Ollama Qwen 2.5 / Llama 3.3 + Faster-Whisper</strong>)
                diretamente em um servidor ou workstation com GPU dentro da rede da sua empresa.
                Nenhum áudio ou texto sai para a internet.
              </p>
            </div>

            <div className="p-6 rounded-3xl bg-white/10 border border-white/15 space-y-4">
              <div className="w-11 h-11 rounded-2xl bg-[#0b57d0] text-white flex items-center justify-center">
                <Server className="w-5 h-5" />
              </div>
              <span className="inline-block px-2.5 py-0.5 rounded-full bg-[#a8c7fa]/20 text-[#a8c7fa] text-[11px] font-semibold">
                Equilíbrio Ideal • Sem Hardware Local
              </span>
              <h3 className="text-lg font-semibold">
                2. Nuvem Privada do Cliente + Chave Própria (BYOK)
              </h3>
              <p className="text-xs text-[#d3e3fd] leading-relaxed">
                Subimos os containers Docker na sua conta AWS, Google Cloud ou Azure e conectamos a
                sua própria chave corporativa do <strong>Google Gemini, xAI Grok ou Anthropic Claude</strong>.
                Usuários ilimitados na sua equipe.
              </p>
            </div>

            <div className="p-6 rounded-3xl bg-white/10 border border-white/15 space-y-4">
              <div className="w-11 h-11 rounded-2xl bg-[#0b57d0] text-white flex items-center justify-center">
                <Layers className="w-5 h-5" />
              </div>
              <span className="inline-block px-2.5 py-0.5 rounded-full bg-[#fde293]/20 text-[#fde293] text-[11px] font-semibold">
                Chave na Mão • Gerenciado por Nós
              </span>
              <h3 className="text-lg font-semibold">
                3. Ambiente Dedicado Gerenciado (Single-Tenant)
              </h3>
              <p className="text-xs text-[#d3e3fd] leading-relaxed">
                Não quer se preocupar com infraestrutura? Entregamos uma instância isolada e
                exclusiva para a sua empresa, já personalizada com seus modelos de relatórios,
                monitoramento e suporte técnico contínuo.
              </p>
            </div>
          </div>

          <div className="pt-4 text-center">
            <button
              onClick={() => navigate(isAuthenticated ? '/' : '/login')}
              className="px-8 py-4 rounded-full bg-[#c2e7ff] hover:bg-white text-[#001d35] font-semibold text-sm inline-flex items-center gap-2.5 shadow-sm transition-all cursor-pointer"
            >
              <span>Testar a Plataforma Base Ao Vivo Agora</span>
              <ArrowRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      </section>

      {/* FOOTER */}
      <footer className="py-8 px-6 lg:px-12 bg-[#f8fafd] border-t border-[#e0e3e7] text-center text-xs text-[#444746]">
        <p>
          <strong>Meet Intelligence B2B Custom</strong> — Plataforma de Inteligência de Reuniões
          personalizada sob demanda (Java 21 + Spring Boot 3.3 + React 19 + pgvector + Multi-LLM / IA Local).
        </p>
      </footer>
    </div>
  );
};

