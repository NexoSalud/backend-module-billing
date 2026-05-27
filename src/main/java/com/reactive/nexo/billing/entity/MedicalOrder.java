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
@Table("medical_orders")
public class MedicalOrder {

    @Id
    private Long id;

    @Column("patient_id")
    private Long patientId;

    @Column("professional_id")
    private Long professionalId;

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

    @Column("base_tariff")
    private BigDecimal baseTariff;

    @Column("iss_multiplier")
    private BigDecimal issMultiplier;

    @Column("status")
    private String status;

    @Column("order_date")
    private LocalDate orderDate;

    @Column("order_notes")
    private String orderNotes;

    @Column("diagnosis_code")
    private String diagnosisCode;

    @Column("diagnosis_desc")
    private String diagnosisDesc;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
