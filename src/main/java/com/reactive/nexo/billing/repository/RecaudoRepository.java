package com.reactive.nexo.billing.repository;

import com.reactive.nexo.billing.entity.Recaudo;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RecaudoRepository extends ReactiveCrudRepository<Recaudo, Long> {

    Flux<Recaudo> findByPatientId(Long patientId);

    Mono<Recaudo> findByNumeroComprobante(String numeroComprobante);

    // Estadísticas del dashboard — estados SALDADO y NO_APLICA son "cobrados"
    @Query("SELECT COALESCE(SUM(valor_total),0) FROM recaudos " +
           "WHERE status IN ('SALDADO','NO_APLICA') AND DATE(created_at) = CURRENT_DATE")
    Mono<java.math.BigDecimal> sumTodayConfirmed();

    @Query("SELECT COUNT(*) FROM recaudos " +
           "WHERE status IN ('SALDADO','NO_APLICA') AND DATE(created_at) = CURRENT_DATE")
    Mono<Long> countTodayConfirmed();

    @Query("SELECT COUNT(*) FROM recaudos WHERE status = 'ANULADO' AND DATE(created_at) = CURRENT_DATE")
    Mono<Long> countTodayAnulados();

    @Query("SELECT nextval('recaudo_seq')")
    Mono<Long> nextSequence();

    // Validación unicidad Contrato B: un episodio_id solo puede tener un evento B no-correctivo
    @Query("SELECT COUNT(*) FROM recaudos WHERE episodio_id = :episodioId " +
           "AND status NOT IN ('ANULADO') AND corrige_comprobante_id IS NULL")
    Mono<Long> countActiveByEpisodioId(String episodioId);
}
