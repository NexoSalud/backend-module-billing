package com.reactive.nexo.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateMedicalOrderRequest {

    @NotNull
    private Long patientId;

    @NotNull
    private Long professionalId;

    private Long appointmentId;

    @NotBlank
    private String cupsCode;

    @NotBlank
    private String cupsDescription;

    @NotBlank
    private String serviceType; // CONSULTA, PROCEDIMIENTO, LABORATORIO, IMAGEN, MEDICAMENTO, OTRO

    private String ambito = "AMBULATORIO";

    private BigDecimal baseTariff;

    private BigDecimal issMultiplier;

    private LocalDate orderDate;

    private String orderNotes;

    private String diagnosisCode;

    private String diagnosisDesc;
}
