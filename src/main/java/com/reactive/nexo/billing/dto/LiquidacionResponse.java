package com.reactive.nexo.billing.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de liquidación automática para un servicio.
 * Calcula cuota moderadora según Acuerdo 260/2004 CRES.
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
    private String normativaAplicada; // Ej: "Acuerdo 260/2004 CRES"
    private boolean exento;
    private String motivoExencion;
}
