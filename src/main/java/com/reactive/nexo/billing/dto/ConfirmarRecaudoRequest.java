package com.reactive.nexo.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfirmarRecaudoRequest {

    @NotBlank
    private String medioPago;

    @NotNull
    private BigDecimal valorRecibido;

    private String observaciones;
}
