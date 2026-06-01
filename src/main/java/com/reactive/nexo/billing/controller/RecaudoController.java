package com.reactive.nexo.billing.controller;

import com.reactive.nexo.billing.dto.*;
import com.reactive.nexo.billing.service.RecaudoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/billing/recaudos")
@RequiredArgsConstructor
public class RecaudoController {

    private final RecaudoService recaudoService;

    /**
     * Lista paginada con filtros.
     * GET /api/v1/billing/recaudos?status=SALDADO&cajeroId=1
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

    @GetMapping("/{id}")
    public Mono<RecaudoResponse> getById(@PathVariable Long id) {
        return recaudoService.getById(id);
    }

    @GetMapping("/comprobante/{numero}")
    public Mono<RecaudoResponse> getByComprobante(@PathVariable String numero) {
        return recaudoService.getByComprobante(numero);
    }

    /**
     * Crea recaudo en estado PENDIENTE (o NO_APLICA si es exento).
     * El Contrato B se publica al outbox al confirmar, no al crear.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RecaudoResponse> create(@Valid @RequestBody CreateRecaudoRequest req) {
        return recaudoService.create(req);
    }

    /**
     * Confirma el pago completo: PENDIENTE/PARCIAL → SALDADO.
     * Publica el Contrato B al outbox transaccional.
     */
    @PostMapping("/{id}/confirmar")
    public Mono<RecaudoResponse> confirmar(
            @PathVariable Long id,
            @Valid @RequestBody ConfirmarRecaudoRequest req) {
        return recaudoService.confirmar(id, req);
    }

    /**
     * Registra pago parcial: PENDIENTE → PARCIAL.
     */
    @PostMapping("/{id}/pago-parcial")
    public Mono<RecaudoResponse> pagoParcial(
            @PathVariable Long id,
            @RequestParam BigDecimal valorParcial) {
        return recaudoService.registrarPagoParcial(id, valorParcial);
    }

    /**
     * Anula un recaudo (soft delete + trazabilidad).
     * Requiere motivo y empleado que anula (RN-11).
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
