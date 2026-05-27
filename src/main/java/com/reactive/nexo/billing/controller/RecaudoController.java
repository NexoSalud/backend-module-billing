package com.reactive.nexo.billing.controller;

import com.reactive.nexo.billing.dto.*;
import com.reactive.nexo.billing.service.RecaudoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/billing/recaudos")
@RequiredArgsConstructor
public class RecaudoController {

    private final RecaudoService recaudoService;

    /**
     * Lista paginada de recaudos con filtros.
     * GET /api/v1/billing/recaudos?page=0&size=10&status=CONFIRMADO&cajeroId=1
     */
    @GetMapping
    public Mono<PagedResponse<RecaudoResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cajeroId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) String search) {
        return recaudoService.findAll(page, size, status, cajeroId, patientId, search);
    }

    /**
     * Obtiene un recaudo por ID con sus ítems.
     */
    @GetMapping("/{id}")
    public Mono<RecaudoResponse> getById(@PathVariable Long id) {
        return recaudoService.getById(id);
    }

    /**
     * Obtiene un recaudo por número de comprobante.
     */
    @GetMapping("/comprobante/{numero}")
    public Mono<RecaudoResponse> getByComprobante(@PathVariable String numero) {
        return recaudoService.getByComprobante(numero);
    }

    /**
     * Crea un recaudo en estado BORRADOR.
     * El frontend crea el borrador al llegar al paso de liquidación.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RecaudoResponse> create(@Valid @RequestBody CreateRecaudoRequest req) {
        return recaudoService.create(req);
    }

    /**
     * Confirma el recaudo (Paso 4: Pago).
     * Cambia estado a CONFIRMADO y marca órdenes como RECAUDADO.
     */
    @PostMapping("/{id}/confirmar")
    public Mono<RecaudoResponse> confirmar(
            @PathVariable Long id,
            @Valid @RequestBody ConfirmarRecaudoRequest req) {
        return recaudoService.confirmar(id, req);
    }

    /**
     * Anula un recaudo. Revierte órdenes a PENDIENTE_RECAUDO.
     */
    @PostMapping("/{id}/anular")
    public Mono<RecaudoResponse> anular(
            @PathVariable Long id,
            @RequestParam Long anuladoPor,
            @RequestParam String motivo) {
        return recaudoService.anular(id, anuladoPor, motivo);
    }

    /**
     * Estadísticas del dashboard de recaudo.
     */
    @GetMapping("/stats/dashboard")
    public Mono<DashboardStatsResponse> getDashboardStats() {
        return recaudoService.getDashboardStats();
    }
}
