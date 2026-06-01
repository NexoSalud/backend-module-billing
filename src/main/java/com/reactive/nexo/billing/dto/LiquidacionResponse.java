package com.reactive.nexo.billing.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * Respuesta de liquidación automática.
 * Implementa lógica de cobro v2.1 — Contrato B NexoSalud HIS.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiquidacionResponse {
    private String cupsCode;
    private String cupsDescription;
    private String serviceType;
    private BigDecimal tarifaBase;
    private BigDecimal descuentoConvenio;
    private BigDecimal cuotaModeradora;
    private BigDecimal copago;
    private BigDecimal valorACobrar;
    private String regimen;
    private String categoria;
    private String tipoCobro;           // cuota_moderadora | copago | particular | exento
    private String normativaAplicada;
    private boolean exento;
    private String motivoExencion;
    // Alertas para el cajero (RN-06)
    private Boolean alertaTopeEvento;
    private Boolean alertaTopeAnual;
}
