package com.reactive.nexo.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RecaudoItemRequest {
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
}
