package com.reactive.nexo.billing.repository;

import com.reactive.nexo.billing.entity.RecaudoItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface RecaudoItemRepository extends ReactiveCrudRepository<RecaudoItem, Long> {

    Flux<RecaudoItem> findByRecaudoId(Long recaudoId);
}
