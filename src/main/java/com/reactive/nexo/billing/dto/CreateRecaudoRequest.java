package com.reactive.nexo.billing.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateRecaudoRequest {

    @NotNull
    private Long patientId;

    @NotNull
    private Long cajeroId;

    private Long sedeId;

    private String epsNombre;

    private String regimen; // CONTRIBUTIVO, SUBSIDIADO, ESPECIAL, PARTICULAR

    private String medioPago; // EFECTIVO, TARJETA, TRANSFERENCIA

    private BigDecimal valorRecibido;

    private String observaciones;

    @NotNull
    private List<RecaudoItemRequest> items;
}
