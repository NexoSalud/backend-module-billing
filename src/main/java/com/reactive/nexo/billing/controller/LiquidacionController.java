package com.reactive.nexo.billing.controller;

import com.reactive.nexo.billing.dto.LiquidacionResponse;
import com.reactive.nexo.billing.entity.CupsTarifa;
import com.reactive.nexo.billing.repository.CupsTarifaRepository;
import com.reactive.nexo.billing.service.LiquidacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/billing/liquidacion")
@RequiredArgsConstructor
public class LiquidacionController {

    private final LiquidacionService liquidacionService;
    private final CupsTarifaRepository cupsTarifaRepository;

    /**
     * Calcula la liquidación completa para un servicio.
     * Implementa los 5 pasos del spec: exención → tipo cobro → valor exacto.
     *
     * GET /api/v1/billing/liquidacion/calcular
     *   ?cupsCode=890201&serviceType=CONSULTA&regimen=CONTRIBUTIVO
     *   &rolAfiliado=BENEFICIARIO&categoria=B&esPyd=false&patientId=1
     */
    @GetMapping("/calcular")
    public Mono<LiquidacionResponse> calcular(
            @RequestParam String cupsCode,
            @RequestParam String serviceType,
            @RequestParam String regimen,
            @RequestParam(defaultValue = "COTIZANTE") String rolAfiliado,
            @RequestParam(defaultValue = "B") String categoria,
            @RequestParam(required = false) BigDecimal tarifaBase,
            @RequestParam(defaultValue = "false") boolean esPyd,
            @RequestParam(required = false) String exencionCodigo,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) String fechaAtencion) {

        LocalDate fecha = fechaAtencion != null ? LocalDate.parse(fechaAtencion) : LocalDate.now();

        return liquidacionService.liquidar(
                cupsCode, serviceType, regimen, rolAfiliado, categoria,
                tarifaBase, esPyd, exencionCodigo, patientId, fecha);
    }

    /**
     * Busca códigos CUPS por código o descripción.
     * GET /api/v1/billing/liquidacion/cups/search?q=hemograma
     */
    @GetMapping("/cups/search")
    public Flux<CupsTarifa> searchCups(@RequestParam String q) {
        return cupsTarifaRepository.searchByCodeOrDescription("%" + q + "%");
    }

    /**
     * Obtiene tarifa de un código CUPS específico.
     */
    @GetMapping("/cups/{code}")
    public Mono<CupsTarifa> getCupsByCode(@PathVariable String code) {
        return cupsTarifaRepository.findByCupsCode(code);
    }
}
