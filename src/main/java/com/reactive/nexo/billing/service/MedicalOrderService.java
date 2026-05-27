package com.reactive.nexo.billing.service;

import com.reactive.nexo.billing.dto.CreateMedicalOrderRequest;
import com.reactive.nexo.billing.dto.LiquidacionResponse;
import com.reactive.nexo.billing.dto.MedicalOrderResponse;
import com.reactive.nexo.billing.entity.MedicalOrder;
import com.reactive.nexo.billing.repository.CupsTarifaRepository;
import com.reactive.nexo.billing.repository.MedicalOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MedicalOrderService {

    private final MedicalOrderRepository orderRepository;
    private final CupsTarifaRepository cupsTarifaRepository;
    private final LiquidacionService liquidacionService;

    /**
     * Obtiene todas las órdenes médicas pendientes de recaudo para un paciente.
     * Incluye citas del día + órdenes activas de Historia Clínica.
     */
    public Flux<MedicalOrderResponse> getPendingOrdersByPatient(Long patientId, String regimen, String categoria) {
        return orderRepository.findPendingByPatient(patientId)
                .flatMap(order -> enrichWithLiquidacion(order, regimen, categoria));
    }

    public Flux<MedicalOrderResponse> getAllOrdersByPatient(Long patientId) {
        return orderRepository.findByPatientId(patientId)
                .map(this::toResponse);
    }

    public Mono<MedicalOrderResponse> createOrder(CreateMedicalOrderRequest req) {
        return cupsTarifaRepository.findByCupsCode(req.getCupsCode())
                .defaultIfEmpty(com.reactive.nexo.billing.entity.CupsTarifa.builder()
                        .cupsCode(req.getCupsCode())
                        .descripcion(req.getCupsDescription())
                        .tarifaIss2001(req.getBaseTariff() != null ? req.getBaseTariff() : BigDecimal.ZERO)
                        .build())
                .flatMap(tarifa -> {
                    MedicalOrder order = MedicalOrder.builder()
                            .patientId(req.getPatientId())
                            .professionalId(req.getProfessionalId())
                            .appointmentId(req.getAppointmentId())
                            .cupsCode(req.getCupsCode())
                            .cupsDescription(req.getCupsDescription() != null ? req.getCupsDescription() : tarifa.getDescripcion())
                            .serviceType(req.getServiceType())
                            .ambito(req.getAmbito() != null ? req.getAmbito() : "AMBULATORIO")
                            .baseTariff(req.getBaseTariff() != null ? req.getBaseTariff() : tarifa.getTarifaIss2001())
                            .issMultiplier(req.getIssMultiplier() != null ? req.getIssMultiplier() : BigDecimal.ONE)
                            .status("PENDIENTE_RECAUDO")
                            .orderDate(req.getOrderDate() != null ? req.getOrderDate() : LocalDate.now())
                            .orderNotes(req.getOrderNotes())
                            .diagnosisCode(req.getDiagnosisCode())
                            .diagnosisDesc(req.getDiagnosisDesc())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return orderRepository.save(order);
                })
                .map(this::toResponse);
    }

    public Mono<MedicalOrderResponse> updateStatus(Long id, String status) {
        return orderRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Orden no encontrada")))
                .flatMap(order -> {
                    order.setStatus(status);
                    order.setUpdatedAt(LocalDateTime.now());
                    return orderRepository.save(order);
                })
                .map(this::toResponse);
    }

    private Mono<MedicalOrderResponse> enrichWithLiquidacion(MedicalOrder order, String regimen, String categoria) {
        if (regimen == null || categoria == null) {
            return Mono.just(toResponse(order));
        }
        return liquidacionService.liquidar(
                order.getCupsCode(),
                order.getServiceType(),
                regimen,
                categoria,
                order.getBaseTariff()
        ).map(liq -> {
            MedicalOrderResponse resp = toResponse(order);
            resp.setCuotaModeradora(liq.getCuotaModeradora());
            resp.setDescuentoConvenio(liq.getDescuentoConvenio());
            resp.setValorACobrar(liq.getValorACobrar());
            return resp;
        });
    }

    private MedicalOrderResponse toResponse(MedicalOrder o) {
        return MedicalOrderResponse.builder()
                .id(o.getId())
                .patientId(o.getPatientId())
                .professionalId(o.getProfessionalId())
                .appointmentId(o.getAppointmentId())
                .cupsCode(o.getCupsCode())
                .cupsDescription(o.getCupsDescription())
                .serviceType(o.getServiceType())
                .ambito(o.getAmbito())
                .baseTariff(o.getBaseTariff())
                .issMultiplier(o.getIssMultiplier())
                .status(o.getStatus())
                .orderDate(o.getOrderDate())
                .orderNotes(o.getOrderNotes())
                .diagnosisCode(o.getDiagnosisCode())
                .diagnosisDesc(o.getDiagnosisDesc())
                .createdAt(o.getCreatedAt())
                .build();
    }
}
