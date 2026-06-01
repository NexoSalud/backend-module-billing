package com.reactive.nexo.billing.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecaudoItemResponse {
    private Long id;
    private Long recaudoId;
    private Long medicalOrderId;
    private Long appointmentId;
    private String cupsCode;
    private String cupsDescription;
    private String serviceType;
    private String ambito;
    private Long professionalId;
    private String professionalName;
    private LocalDate serviceDate;
    private BigDecimal baseTariff;
    private BigDecimal descuentoConvenio;
    private BigDecimal cuotaModeradora;
    private BigDecimal copago;
    private BigDecimal valorCobrado;
    private Boolean exento;
    private String exencionCodigo;
    private Boolean topeEventoAplicado;
    private Boolean topeAnualAplicado;
}
