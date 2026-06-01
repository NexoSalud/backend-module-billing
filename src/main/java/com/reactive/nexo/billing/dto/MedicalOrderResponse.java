package com.reactive.nexo.billing.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalOrderResponse {
    private Long id;
    private Long patientId;
    private Long professionalId;
    private String professionalName;
    private Long appointmentId;
    private String episodioId;
    private String cupsCode;
    private String cupsDescription;
    private String serviceType;
    private String ambito;
    private BigDecimal baseTariff;
    private BigDecimal issMultiplier;
    private Boolean esPyd;
    private String status;
    private LocalDate orderDate;
    private String orderNotes;
    private String diagnosisCode;
    private String diagnosisDesc;
    // Liquidación calculada
    private String tipoCobro;
    private BigDecimal cuotaModeradora;
    private BigDecimal descuentoConvenio;
    private BigDecimal valorACobrar;
    private Boolean alertaTopeEvento;
    private Boolean alertaTopeAnual;
    private LocalDateTime createdAt;
}
