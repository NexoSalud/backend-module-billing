package com.reactive.nexo.billing.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("recaudo_items")
public class RecaudoItem {

    @Id
    private Long id;

    @Column("recaudo_id")
    private Long recaudoId;

    @Column("medical_order_id")
    private Long medicalOrderId;

    @Column("appointment_id")
    private Long appointmentId;

    @Column("cups_code")
    private String cupsCode;

    @Column("cups_description")
    private String cupsDescription;

    @Column("service_type")
    private String serviceType;

    @Column("ambito")
    private String ambito;

    @Column("professional_id")
    private Long professionalId;

    @Column("professional_name")
    private String professionalName;

    @Column("service_date")
    private LocalDate serviceDate;

    @Column("base_tariff")
    private BigDecimal baseTariff;

    @Column("descuento_convenio")
    private BigDecimal descuentoConvenio;

    @Column("cuota_moderadora")
    private BigDecimal cuotaModeradora;

    @Column("copago")
    private BigDecimal copago;

    @Column("tope_evento_aplicado")
    private Boolean topeEventoAplicado;

    @Column("tope_anual_aplicado")
    private Boolean topeAnualAplicado;

    @Column("valor_cobrado")
    private BigDecimal valorCobrado;

    @Column("exento")
    private Boolean exento;

    @Column("exencion_codigo")
    private String exencionCodigo;

    @Column("created_at")
    private LocalDateTime createdAt;
}
