package com.reactive.nexo.billing.controller;

import com.reactive.nexo.billing.dto.LiquidacionResponse;
import com.reactive.nexo.billing.repository.CupsTarifaRepository;
import com.reactive.nexo.billing.service.LiquidacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/billing/liquidacion")
@RequiredArgsConstructor
public class LiquidacionController {

    private final LiquidacionService liquidacionService;
    private final CupsTarifaRepository cupsTarifaRepository;

    /**
     * Calcula la liquidación para un servicio específico.
     * GET /api/v1/billing/liquidacion/calcular?cupsCode=890201&serviceType=CONSULTA&regimen=CONTRIBUTIVO&categoria=B
     */
    @GetMapping("/calcular")
    public Mono<LiquidacionResponse> calcular(
            @RequestParam String cupsCode,
            @RequestParam String serviceType,
            @RequestParam String regimen,
            @RequestParam(defaultValue = "B") String categoria,
            @RequestParam(required = false) BigDecimal tarifaBase) {
        return liquidacionService.liquidar(cupsCode, serviceType, regimen, categoria, tarifaBase);
    }

    /**
     * Busca códigos CUPS por código o descripción.
     * GET /api/v1/billing/liquidacion/cups/search?q=hemograma
     */
    @GetMapping("/cups/search")
    public Flux<com.reactive.nexo.billing.entity.CupsTarifa> searchCups(@RequestParam String q) {
        return cupsTarifaRepository.searchByCodeOrDescription("%" + q + "%");
    }

    /**
     * Obtiene tarifa de un código CUPS específico.
     */
    @GetMapping("/cups/{code}")
    public Mono<com.reactive.nexo.billing.entity.CupsTarifa> getCupsByCode(@PathVariable String code) {
        return cupsTarifaRepository.findByCupsCode(code);
    }
}
