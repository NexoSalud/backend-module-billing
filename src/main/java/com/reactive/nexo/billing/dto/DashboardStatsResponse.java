package com.reactive.nexo.billing.dto;

import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsResponse {
    private BigDecimal totalRecaudadoHoy;
    private Long transaccionesHoy;
    private BigDecimal promedioPorTransaccion;
    private Long anulacionesHoy;
}
