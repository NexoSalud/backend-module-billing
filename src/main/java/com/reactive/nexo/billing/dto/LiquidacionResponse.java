package com.reactive.nexo.billing.dto;

import lombok.*;
import java.math.BigDecimal;

/**
 * Respuesta de liquidación automática para un servicio.
 * Normativa: Acuerdo 260/2004 CRES + Circular 048/2025 (UVB 2026) + Decreto 1652/2022.
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
    /** cuota_moderadora | copago | particular | exento */
    private String tipoCobro;
    private String normativaAplicada;
    private boolean exento;
    private String motivoExencion;
    /** RN-06: tope por evento alcanzado */
    private Boolean alertaTopeEvento;
    /** RN-07: tope anual local alcanzado */
    private Boolean alertaTopeAnual;
}
