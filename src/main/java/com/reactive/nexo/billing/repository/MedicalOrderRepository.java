package com.reactive.nexo.billing.repository;

import com.reactive.nexo.billing.entity.MedicalOrder;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MedicalOrderRepository extends ReactiveCrudRepository<MedicalOrder, Long> {

    Flux<MedicalOrder> findByPatientIdAndStatus(Long patientId, String status);

    Flux<MedicalOrder> findByPatientId(Long patientId);

    @Query("SELECT * FROM medical_orders WHERE patient_id = :patientId AND status = 'PENDIENTE_RECAUDO' ORDER BY order_date DESC")
    Flux<MedicalOrder> findPendingByPatient(Long patientId);

    @Query("UPDATE medical_orders SET status = :status, updated_at = NOW() WHERE id = :id")
    Mono<Void> updateStatus(Long id, String status);

    @Query("UPDATE medical_orders SET status = 'EN_RECAUDO', updated_at = NOW() WHERE id IN (:ids)")
    Mono<Void> markAsInRecaudo(java.util.List<Long> ids);
}
