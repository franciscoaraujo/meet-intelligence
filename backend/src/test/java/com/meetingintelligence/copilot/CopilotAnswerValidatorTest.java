package com.meetingintelligence.copilot;

import com.meetingintelligence.domain.copilot.CopilotAnswer;
import com.meetingintelligence.domain.copilot.CopilotAnswer.Sections;
import com.meetingintelligence.domain.copilot.CopilotAnswerValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotAnswerValidatorTest {

    private static final String RESUME = "Engenheiro Java e Spring Boot. RabbitMQ com Retry/DLQ. Transactional Outbox no OrionPay.";
    private static final String SPOKEN = ("O Transactional Outbox resolve a publicação confiável de eventos. Eu gravo o evento na mesma "
            + "transação do dado de negócio e um processo separado publica no broker. No OrionPay eu usei esse padrão com "
            + "RabbitMQ e retry com DLQ, o que garantiu que nenhum evento de pagamento fosse perdido mesmo com falhas.").trim();

    private static CopilotAnswer answer(String spoken, Sections sections) {
        return new CopilotAnswer(true, "INTERMEDIATE", spoken, sections, List.of(), List.of(), 0.9, false);
    }

    private static Sections full() {
        return new Sections("Gravo o evento na mesma transação.", "Tabela outbox + relay.", "No OrionPay com RabbitMQ.", null, null);
    }

    @Test
    @DisplayName("Resposta fiel ao currículo e completa é válida")
    void validAnswer() {
        assertThat(CopilotAnswerValidator.validate(answer(SPOKEN, full()), "como você garante consistência entre microsserviços", RESUME))
                .isEmpty();
    }

    @Test
    @DisplayName("CA-01 / RN-01: experiência com tecnologia fora do currículo é rejeitada")
    void inventedExperienceRejected() {
        String spoken = SPOKEN + " Também trabalhei com Kafka em produção por três anos.";
        List<String> problems = CopilotAnswerValidator.validate(answer(spoken, full()), "fale sobre mensageria", RESUME);
        assertThat(problems).anyMatch(p -> p.contains("kafka"));
    }

    @Test
    @DisplayName("Citar uma tecnologia de forma conceitual (sem afirmar experiência) é permitido")
    void conceptualMentionAllowed() {
        String spoken = SPOKEN + " O Kafka também é uma opção quando o volume é muito alto.";
        assertThat(CopilotAnswerValidator.validate(answer(spoken, full()), "fale sobre mensageria", RESUME)).isEmpty();
    }

    @Test
    @DisplayName("CA-02 / RF-04: pergunta de arquitetura exige resposta direta, explicação e exemplo ou trade-off")
    void technicalSectionsRequired() {
        Sections incomplete = new Sections("Uso Outbox.", null, null, null, null);
        assertThat(CopilotAnswerValidator.validate(answer(SPOKEN, incomplete), "explique a arquitetura de microsserviços", RESUME))
                .anyMatch(p -> p.contains("pergunta técnica"));
    }

    @Test
    @DisplayName("Versão falada curta ou longa demais é rejeitada")
    void spokenLength() {
        assertThat(CopilotAnswerValidator.validate(answer("Sim.", full()), "o que é SOLID", RESUME))
                .anyMatch(p -> p.contains("palavras"));
    }

    @Test
    @DisplayName("Quando não é pergunta ao candidato, não há o que validar")
    void notAQuestion() {
        CopilotAnswer notQuestion = new CopilotAnswer(false, null, null, null, null, null, null, false);
        assertThat(CopilotAnswerValidator.validate(notQuestion, "aqui usamos Kafka", RESUME)).isEmpty();
    }
}
