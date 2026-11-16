package com.meetingintelligence.infrastructure.async;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executa tarefas de uma mesma reunião em série e na ordem de chegada; reuniões diferentes rodam em
 * paralelo no pool limitado (spec 005, RF-10). Estado em memória: vale para uma instância do backend
 * (com várias instâncias, a evolução prevista é usar o RabbitMQ já presente no compose).
 */
@Component
@Slf4j
public class MeetingSerialExecutor {

    private final Executor pool;
    private final Map<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final AtomicInteger queued = new AtomicInteger();

    @Autowired
    public MeetingSerialExecutor(org.springframework.beans.factory.ObjectProvider<MeterRegistry> meters,
                                 @org.springframework.beans.factory.annotation.Qualifier("taskExecutor") Executor pool) {
        this.pool = pool;
        meters.ifAvailable(m -> m.gauge("meeting.serial.queue.size", queued));
    }

    /** Construtor para testes (ex.: executor direto). */
    public MeetingSerialExecutor(Executor pool) {
        this.pool = pool;
    }

    public CompletableFuture<Void> submit(UUID meetingId, Runnable task) {
        queued.incrementAndGet();
        Runnable safe = () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Falha em tarefa da reunião {}: {}", meetingId, e.getMessage(), e);
            } finally {
                queued.decrementAndGet();
            }
        };
        CompletableFuture<Void> next = tails.compute(meetingId, (id, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous).thenRunAsync(safe, pool));
        // Remove a cauda quando ela termina sem novas tarefas atrás (evita crescer o mapa indefinidamente)
        next.whenComplete((v, e) -> tails.remove(meetingId, next));
        return next;
    }
}
