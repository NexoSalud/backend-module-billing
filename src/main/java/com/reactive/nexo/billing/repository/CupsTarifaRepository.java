package com.reactive.nexo.billing.repository;

import com.reactive.nexo.billing.entity.CupsTarifa;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CupsTarifaRepository extends ReactiveCrudRepository<CupsTarifa, Long> {

    Mono<CupsTarifa> findByCupsCode(String cupsCode);

    @Query("SELECT * FROM cups_tarifas WHERE activo = true AND (cups_code ILIKE :q OR descripcion ILIKE :q) ORDER BY cups_code LIMIT 20")
    Flux<CupsTarifa> searchByCodeOrDescription(String q);
}
