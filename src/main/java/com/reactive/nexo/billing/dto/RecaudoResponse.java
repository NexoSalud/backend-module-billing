package com.reactive.nexo.billing.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecaudoResponse {
    private Long id;
    private String numeroComprobante;
    private Long patientId;
    private String patientName;
    private String patientIdentification;
    private Long cajeroId;
    private String cajeroName;
    private Long sedeId;
    private String epsNombre;
    private String regimen;
    private String status;
    private String medioPago;
    private BigDecimal valorTotal;
    private BigDecimal valorRecibido;
    private BigDecimal cambio;
    private String observaciones;
    private LocalDateTime confirmadoAt;
    private LocalDateTime createdAt;
    private List<RecaudoItemResponse> items;
}
