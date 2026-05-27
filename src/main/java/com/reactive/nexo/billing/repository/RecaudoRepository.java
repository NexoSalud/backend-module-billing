package com.reactive.nexo.billing.repository;

import com.reactive.nexo.billing.entity.Recaudo;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RecaudoRepository extends ReactiveCrudRepository<Recaudo, Long> {

    Flux<Recaudo> findByPatientId(Long patientId);

    Mono<Recaudo> findByNumeroComprobante(String numeroComprobante);

    @Query("SELECT * FROM recaudos WHERE cajero_id = :cajeroId ORDER BY created_at DESC LIMIT :size OFFSET :offset")
    Flux<Recaudo> findByCajeroId(Long cajeroId, int size, long offset);

    @Query("SELECT COUNT(*) FROM recaudos WHERE 1=1" +
           " AND (:status IS NULL OR status = :status)" +
           " AND (:cajeroId IS NULL OR cajero_id = :cajeroId)" +
           " AND (:patientId IS NULL OR patient_id = :patientId)")
    Mono<Long> countFiltered(String status, Long cajeroId, Long patientId);

    @Query("SELECT COALESCE(SUM(valor_total),0) FROM recaudos WHERE status = 'CONFIRMADO' AND DATE(created_at) = CURRENT_DATE")
    Mono<java.math.BigDecimal> sumTodayConfirmed();

    @Query("SELECT COUNT(*) FROM recaudos WHERE status = 'CONFIRMADO' AND DATE(created_at) = CURRENT_DATE")
    Mono<Long> countTodayConfirmed();

    @Query("SELECT COUNT(*) FROM recaudos WHERE status = 'ANULADO' AND DATE(created_at) = CURRENT_DATE")
    Mono<Long> countTodayAnulados();

    @Query("SELECT nextval('recaudo_seq')")
    Mono<Long> nextSequence();
}
