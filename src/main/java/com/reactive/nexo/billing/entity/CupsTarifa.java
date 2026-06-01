package com.reactive.nexo.billing.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("cups_tarifas")
public class CupsTarifa {

    @Id
    private Long id;

    @Column("cups_code")
    private String cupsCode;

    @Column("descripcion")
    private String descripcion;

    @Column("grupo")
    private String grupo;

    @Column("subgrupo")
    private String subgrupo;

    @Column("tarifa_iss_2001")
    private BigDecimal tarifaIss2001;

    @Column("tarifa_soat")
    private BigDecimal tarifaSoat;

    @Column("unidad_medida")
    private String unidadMedida;

    @Column("es_pyd")
    private Boolean esPyd;

    @Column("activo")
    private Boolean activo;

    @Column("created_at")
    private LocalDateTime createdAt;
}
