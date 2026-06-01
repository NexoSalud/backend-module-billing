package com.reactive.nexo.billing.repository;

import com.reactive.nexo.billing.entity.ContratoBOutbox;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ContratoBOutboxRepository extends ReactiveCrudRepository<ContratoBOutbox, Long> {

    Mono<ContratoBOutbox> findByEventoId(UUID eventoId);

    @Query("SELECT * FROM contrato_b_outbox WHERE status IN ('PENDIENTE','FALLIDO') " +
           "AND (proximo_intento IS NULL OR proximo_intento <= NOW()) " +
           "AND intentos < 5 ORDER BY created_at LIMIT 50")
    Flux<ContratoBOutbox> findPendingToSend();

    @Query("SELECT COUNT(*) FROM contrato_b_outbox WHERE episodio_id = :episodioId " +
           "AND status != 'DEAD_LETTER'")
    Mono<Long> countByEpisodioId(String episodioId);
}
