import React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, Check, Mail, Mic } from 'lucide-react';
import {
  ADHESION_STEPS,
  INTERVIEW_PLANS,
  contactHref,
  trialPath,
  type InterviewPlan,
} from '../../features/landing/interviewPlans';

interface Props {
  isAuthenticated: boolean;
  /** E-mail de contato; padrão: VITE_CONTACT_EMAIL. */
  contactEmail?: string | null;
}

/**
 * Spec 019: como aderir ao copiloto de entrevistas (teste grátis + planos com IA incluída).
 * Reuniões e outros usos seguem os modelos de IA local apresentados no restante da página.
 */
export const InterviewPlansSection: React.FC<Props> = ({
  isAuthenticated,
  contactEmail = import.meta.env.VITE_CONTACT_EMAIL,
}) => {
  const navigate = useNavigate();

  const act = (plan: InterviewPlan) => {
    if (plan.action === 'TRIAL') {
      navigate(trialPath(isAuthenticated));
      return;
    }
    const href = contactHref(plan, contactEmail);
    if (href.startsWith('mailto:')) {
      window.location.href = href;
    } else {
      navigate(href);
    }
  };

  return (
    <section
      id="planos-entrevista"
      className="py-16 px-6 lg:px-12 bg-white border-t border-[#e0e3e7]"
      data-testid="interview-plans"
    >
      <div className="max-w-6xl mx-auto space-y-10">
        <div className="text-center space-y-3 max-w-2xl mx-auto">
          <span className="text-xs font-semibold uppercase tracking-wider text-[#0b57d0] inline-flex items-center gap-1.5">
            <Mic className="w-3.5 h-3.5" /> Copiloto de entrevistas
          </span>
          <h2 className="text-2xl md:text-3xl font-semibold text-[#1f1f1f]">Como aderir</h2>
          <p className="text-sm text-[#444746] leading-relaxed">
            Transcrição e respostas por IA já incluídas no plano: você não precisa criar nem configurar
            nenhuma chave de API. Comece com um teste grátis.
          </p>
        </div>

        <ol className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {ADHESION_STEPS.map((step, i) => (
            <li key={step.title} className="p-5 rounded-2xl bg-[#f8fafd] border border-[#e0e3e7] flex gap-3">
              <span className="w-7 h-7 shrink-0 rounded-full bg-[#0b57d0] text-white text-xs font-semibold flex items-center justify-center">
                {i + 1}
              </span>
              <div>
                <p className="text-sm font-semibold text-[#1f1f1f]">{step.title}</p>
                <p className="text-xs text-[#444746] mt-1 leading-relaxed">{step.text}</p>
              </div>
            </li>
          ))}
        </ol>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          {INTERVIEW_PLANS.map((plan) => (
            <div
              key={plan.id}
              data-testid={`plan-${plan.id}`}
              className={`p-6 rounded-2xl border flex flex-col justify-between gap-5 ${
                plan.highlighted
                  ? 'bg-[#e8f0fe] border-[#0b57d0] ring-2 ring-[#0b57d0]/15'
                  : 'bg-white border-[#e0e3e7]'
              }`}
            >
              <div className="space-y-3">
                <div className="flex items-center justify-between gap-2">
                  <h3 className="text-base font-semibold text-[#1f1f1f]">{plan.name}</h3>
                  {plan.highlighted && (
                    <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#0b57d0] text-white uppercase tracking-wider">
                      Mais indicado
                    </span>
                  )}
                </div>
                <p className="text-2xl font-semibold text-[#041e49]" data-testid={`plan-price-${plan.id}`}>
                  {plan.price}
                </p>
                <p className="text-xs text-[#444746]">{plan.description}</p>
                <ul className="space-y-2 pt-1">
                  {plan.features.map((f) => (
                    <li key={f} className="text-xs text-[#1f1f1f] flex items-start gap-2">
                      <Check className="w-3.5 h-3.5 mt-0.5 shrink-0 text-[#146c2e]" />
                      <span>{f}</span>
                    </li>
                  ))}
                </ul>
              </div>
              <button
                onClick={() => act(plan)}
                data-testid={`plan-cta-${plan.id}`}
                className={`w-full py-3 rounded-full text-sm font-semibold inline-flex items-center justify-center gap-2 transition-all cursor-pointer ${
                  plan.action === 'TRIAL' || plan.highlighted
                    ? 'bg-[#0b57d0] hover:bg-[#0842a0] text-white'
                    : 'bg-white border border-[#c4c7c5] text-[#0b57d0] hover:bg-[#f8fafd]'
                }`}
              >
                {plan.action === 'CONTACT' && <Mail className="w-4 h-4" />}
                <span>{plan.action === 'TRIAL' && isAuthenticated ? 'Ir para o painel' : plan.cta}</span>
                {plan.action === 'TRIAL' && <ArrowRight className="w-4 h-4" />}
              </button>
            </div>
          ))}
        </div>

        <p className="text-xs text-center text-[#444746] max-w-2xl mx-auto leading-relaxed">
          Para reuniões e outros usos, continuam valendo os modelos com IA local ou com a sua própria chave
          apresentados nesta página.
        </p>
      </div>
    </section>
  );
};
