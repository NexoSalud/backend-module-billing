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
     * Órdenes pendientes de recaudo con liquidación automática.
     * RN-02: en consulta externa el recaudo ocurre antes de la atención.
     *
     * GET /api/v1/billing/orders/pending
     *   ?patientId=1&regimen=CONTRIBUTIVO&rolAfiliado=BENEFICIARIO&categoria=B
     */
    @GetMapping("/pending")
    public Flux<MedicalOrderResponse> getPending(
            @RequestParam Long patientId,
            @RequestParam(required = false) String regimen,
            @RequestParam(required = false, defaultValue = "COTIZANTE") String rolAfiliado,
            @RequestParam(required = false, defaultValue = "B") String categoria) {
        return orderService.getPendingOrdersByPatient(patientId, regimen, rolAfiliado, categoria);
    }

    @GetMapping
    public Flux<MedicalOrderResponse> getByPatient(@RequestParam Long patientId) {
        return orderService.getAllOrdersByPatient(patientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<MedicalOrderResponse> create(@Valid @RequestBody CreateMedicalOrderRequest req) {
        return orderService.createOrder(req);
    }

    @PatchMapping("/{id}/status")
    public Mono<MedicalOrderResponse> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        return orderService.updateStatus(id, status);
    }
}
