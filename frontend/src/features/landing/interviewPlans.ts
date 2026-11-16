/**
 * Planos do copiloto de entrevistas (spec 019). Nomes, horas e textos ficam só aqui.
 * Sem preço publicado por enquanto (RN-01): "Sob consulta" até o usuário definir os valores.
 */
export type PlanAction = 'TRIAL' | 'CONTACT';

export interface InterviewPlan {
  id: string;
  name: string;
  price: string;
  description: string;
  features: string[];
  action: PlanAction;
  cta: string;
  highlighted?: boolean;
}

export const INTERVIEW_PLANS: InterviewPlan[] = [
  {
    id: 'trial',
    name: 'Teste grátis',
    price: 'Grátis',
    description: 'Conheça o copiloto numa entrevista de teste.',
    features: [
      'Cadastro com sua conta Google',
      'Transcrição e respostas por IA incluídas',
      'Sem cartão de crédito',
    ],
    action: 'TRIAL',
    cta: 'Começar teste grátis',
  },
  {
    id: 'pro',
    name: 'Profissional',
    price: 'Sob consulta',
    description: 'Para quem está em processo seletivo ativo.',
    features: [
      '10 h de entrevista por mês',
      'Transcrição e respostas em tempo real',
      'Base de conhecimento com seu currículo e a vaga',
      'Entrevistas em português, inglês ou francês',
    ],
    action: 'CONTACT',
    cta: 'Fale conosco',
    highlighted: true,
  },
  {
    id: 'intensive',
    name: 'Intensivo',
    price: 'Sob consulta',
    description: 'Para muitas entrevistas no mesmo mês.',
    features: ['30 h de entrevista por mês', 'Tudo do plano Profissional'],
    action: 'CONTACT',
    cta: 'Fale conosco',
  },
];

/** Passos da adesão (RF-03). */
export const ADHESION_STEPS = [
  { title: 'Crie sua conta', text: 'Entre com sua conta Google; o primeiro acesso já é o cadastro.' },
  { title: 'Teste o copiloto', text: 'Faça uma entrevista de teste e veja as respostas aparecerem em tempo real.' },
  { title: 'Escolha um plano', text: 'Fale com a gente para ativar as horas do plano que fizer sentido.' },
];

/**
 * Destino de "Fale conosco" (RF-04): e-mail configurado em VITE_CONTACT_EMAIL, com o plano no assunto;
 * sem e-mail configurado, o cadastro.
 */
export const contactHref = (plan: InterviewPlan, email?: string | null): string => {
  const to = (email ?? '').trim();
  if (!to) return '/login';
  const subject = encodeURIComponent(`Plano ${plan.name} - copiloto de entrevistas`);
  return `mailto:${to}?subject=${subject}`;
};

/** Destino de "Começar teste grátis" (RF-04). */
export const trialPath = (isAuthenticated: boolean): string => (isAuthenticated ? '/' : '/login');
