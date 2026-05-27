package com.reactive.nexo.billing.controller;

import com.reactive.nexo.billing.dto.CreateMedicalOrderRequest;
import com.reactive.nexo.billing.dto.MedicalOrderResponse;
import com.reactive.nexo.billing.service.MedicalOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/billing/orders")
@RequiredArgsConstructor
public class MedicalOrderController {

    private final MedicalOrderService orderService;

    /**
     * Obtiene todas las órdenes pendientes de recaudo para un paciente.
     * Incluye liquidación automática si se provee régimen y categoría.
     *
     * GET /api/v1/billing/orders/pending?patientId=1&regimen=CONTRIBUTIVO&categoria=B
     */
    @GetMapping("/pending")
    public Flux<MedicalOrderResponse> getPending(
            @RequestParam Long patientId,
            @RequestParam(required = false) String regimen,
            @RequestParam(required = false, defaultValue = "B") String categoria) {
        return orderService.getPendingOrdersByPatient(patientId, regimen, categoria);
    }

    /**
     * Todas las órdenes de un paciente (cualquier estado).
     */
    @GetMapping
    public Flux<MedicalOrderResponse> getByPatient(@RequestParam Long patientId) {
        return orderService.getAllOrdersByPatient(patientId);
    }

    /**
     * Crea una nueva orden médica (desde Historia Clínica o manualmente).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<MedicalOrderResponse> create(@Valid @RequestBody CreateMedicalOrderRequest req) {
        return orderService.createOrder(req);
    }

    /**
     * Actualiza el estado de una orden.
     * PATCH /api/v1/billing/orders/{id}/status?status=ANULADO
     */
    @PatchMapping("/{id}/status")
    public Mono<MedicalOrderResponse> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        return orderService.updateStatus(id, status);
    }
}
