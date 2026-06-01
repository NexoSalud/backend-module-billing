package com.reactive.nexo.billing.service;

import com.reactive.nexo.billing.dto.*;
import com.reactive.nexo.billing.entity.Recaudo;
import com.reactive.nexo.billing.entity.RecaudoItem;
import com.reactive.nexo.billing.repository.MedicalOrderRepository;
import com.reactive.nexo.billing.repository.RecaudoItemRepository;
import com.reactive.nexo.billing.repository.RecaudoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Servicio de Recaudo — NexoSalud HIS.
 *
 * Máquina de estados (sección 4 del spec):
 *   PENDIENTE → PARCIAL → SALDADO → ANULADO (con reverso autorizado)
 *   PENDIENTE → NO_APLICA (exentos / tercero pagador)
 *   PENDIENTE → ANULADO
 *
 * Contrato B v2.0: se publica al outbox en la misma operación que confirma el comprobante.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecaudoService {

    private final RecaudoRepository recaudoRepository;
    private final RecaudoItemRepository itemRepository;
    private final MedicalOrderRepository orderRepository;
    private final ContratoBOutboxService outboxService;
    private final DatabaseClient databaseClient;

    // ─── Crear recaudo (estado PENDIENTE) ────────────────────────────────────

    /**
     * Crea un recaudo en estado PENDIENTE con sus ítems.
     * Para exentos crea directamente en NO_APLICA.
     * Marca las órdenes médicas como EN_RECAUDO.
     */
    public Mono<RecaudoResponse> create(CreateRecaudoRequest req) {
        return generateNumeroComprobante()
                .flatMap(numero -> {
                    BigDecimal total = req.getItems().stream()
                            .map(i -> i.getValorCobrado() != null ? i.getValorCobrado() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // Exentos → NO_APLICA directamente (RN-15)
                    boolean esExento = "exento".equalsIgnoreCase(req.getTipoCobro())
                            || (req.getExencionCodigo() != null && !req.getExencionCodigo().isBlank());
                    String statusInicial = esExento ? "NO_APLICA" : "PENDIENTE";

                    Recaudo recaudo = Recaudo.builder()
                            .numeroComprobante(numero)
                            .eventoId(UUID.randomUUID())
                            .episodioId(req.getEpisodioId())
                            .corrigeComprobanteId(req.getCorrigeComprobanteId())
                            .contratoVersion("2.0")
                            .patientId(req.getPatientId())
                            .cajeroId(req.getCajeroId())
                            .sedeId(req.getSedeId())
                            .epsId(req.getEpsId())
                            .epsNombre(req.getEpsNombre())
                            .regimen(req.getRegimen())
                            .rolAfiliado(req.getRolAfiliado())
                            .categoriaIbc(req.getCategoriaIbc())
                            .tipoServicio(req.getTipoServicio())
                            .numeroAutorizacion(req.getNumeroAutorizacion())
                            .esPyd(req.getEsPyd() != null ? req.getEsPyd() : false)
                            .exencionCodigo(req.getExencionCodigo())
                            .tipoCobro(req.getTipoCobro())
                            .status(statusInicial)
                            .medioPago(req.getMedioPago())
                            .valorTotal(total)
                            .valorRecibido(req.getValorRecibido())
                            .cambio(req.getValorRecibido() != null ? req.getValorRecibido().subtract(total) : null)
                            .comprobanteInmutable(true)
                            .observaciones(req.getObservaciones())
                            .fechaAtencion(req.getFechaAtencion() != null ? req.getFechaAtencion() : LocalDate.now())
                            .fechaEmisionEvento(LocalDateTime.now())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return recaudoRepository.save(recaudo);
                })
                .flatMap(saved -> saveItems(saved.getId(), req.getItems())
                        .then(markOrdersInRecaudo(req.getItems()))
                        .then(Mono.defer(() -> {
                            // Exentos publican Contrato B inmediatamente (B cero explícito)
                            if ("NO_APLICA".equals(saved.getStatus())) {
                                return outboxService.publicar(saved).then();
                            }
                            return Mono.empty();
                        }))
                        .then(buildResponse(saved)));
    }

    // ─── Confirmar pago (PENDIENTE/PARCIAL → SALDADO) ────────────────────────

    /**
     * Confirma el pago completo. Publica el Contrato B al outbox.
     * Actualiza acumulado anual de copago si aplica.
     */
    public Mono<RecaudoResponse> confirmar(Long id, ConfirmarRecaudoRequest req) {
        return recaudoRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Recaudo no encontrado")))
                .flatMap(recaudo -> {
                    if (!"PENDIENTE".equals(recaudo.getStatus()) && !"PARCIAL".equals(recaudo.getStatus())) {
                        return Mono.error(new RuntimeException(
                                "Solo se pueden confirmar recaudos en estado PENDIENTE o PARCIAL"));
                    }
                    recaudo.setStatus("SALDADO");
                    recaudo.setMedioPago(req.getMedioPago());
                    recaudo.setValorRecibido(req.getValorRecibido());
                    recaudo.setCambio(req.getValorRecibido().subtract(recaudo.getValorTotal()));
                    recaudo.setObservaciones(req.getObservaciones());
                    recaudo.setConfirmadoAt(LocalDateTime.now());
                    recaudo.setUpdatedAt(LocalDateTime.now());
                    return recaudoRepository.save(recaudo);
                })
                .flatMap(saved ->
                        markOrdersRecaudado(saved.getId())
                        .then(actualizarAcumuladoCopago(saved))
                        .then(outboxService.publicar(saved))  // Contrato B al outbox
                        .then(buildResponse(saved)));
    }

    // ─── Pago parcial (PENDIENTE → PARCIAL) ──────────────────────────────────

    public Mono<RecaudoResponse> registrarPagoParcial(Long id, BigDecimal valorParcial) {
        return recaudoRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Recaudo no encontrado")))
                .flatMap(recaudo -> {
                    if (!"PENDIENTE".equals(recaudo.getStatus())) {
                        return Mono.error(new RuntimeException("Solo se puede registrar pago parcial en estado PENDIENTE"));
                    }
                    recaudo.setStatus("PARCIAL");
                    recaudo.setValorRecibido(valorParcial);
                    recaudo.setUpdatedAt(LocalDateTime.now());
                    return recaudoRepository.save(recaudo);
                })
                .flatMap(this::buildResponse);
    }

    // ─── Anular (SALDADO/PENDIENTE → ANULADO) ────────────────────────────────

    /**
     * Anula un recaudo. Soft delete — nunca se borra el registro original (RN-15).
     * Revierte órdenes a PENDIENTE_RECAUDO.
     * Si había copago acumulado, lo descuenta del acumulado anual.
     */
    public Mono<RecaudoResponse> anular(Long id, Long anuladoPor, String motivo) {
        return recaudoRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Recaudo no encontrado")))
                .flatMap(recaudo -> {
                    if ("ANULADO".equals(recaudo.getStatus())) {
                        return Mono.error(new RuntimeException("El recaudo ya está anulado"));
                    }
                    recaudo.setStatus("ANULADO");
                    recaudo.setAnulacionMotivo(motivo);
                    recaudo.setAnuladoPor(anuladoPor);
                    recaudo.setAnuladoAt(LocalDateTime.now());
                    recaudo.setUpdatedAt(LocalDateTime.now());
                    return recaudoRepository.save(recaudo);
                })
                .flatMap(saved ->
                        revertOrdersToPending(saved.getId())
                        .then(revertirAcumuladoCopago(saved))
                        .then(buildResponse(saved)));
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    public Mono<RecaudoResponse> getById(Long id) {
        return recaudoRepository.findById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("Recaudo no encontrado")))
                .flatMap(this::buildResponse);
    }

    public Mono<RecaudoResponse> getByComprobante(String numero) {
        return recaudoRepository.findByNumeroComprobante(numero)
                .switchIfEmpty(Mono.error(new RuntimeException("Comprobante no encontrado")))
                .flatMap(this::buildResponse);
    }

    public Mono<PagedResponse<RecaudoResponse>> findAll(int page, int size,
                                                         String status, Long cajeroId,
                                                         Long patientId, String search) {
        StringBuilder sql = new StringBuilder("SELECT r.* FROM recaudos r WHERE 1=1");
        if (status != null && !status.isBlank()) sql.append(" AND r.status = :status");
        if (cajeroId != null) sql.append(" AND r.cajero_id = :cajeroId");
        if (patientId != null) sql.append(" AND r.patient_id = :patientId");
        if (search != null && !search.isBlank()) sql.append(" AND r.numero_comprobante ILIKE :search");
        sql.append(" ORDER BY r.created_at DESC LIMIT :size OFFSET :offset");

        String countSql = "SELECT COUNT(*) FROM recaudos r WHERE 1=1"
                + (status != null && !status.isBlank() ? " AND r.status = :status" : "")
                + (cajeroId != null ? " AND r.cajero_id = :cajeroId" : "")
                + (patientId != null ? " AND r.patient_id = :patientId" : "")
                + (search != null && !search.isBlank() ? " AND r.numero_comprobante ILIKE :search" : "");

        DatabaseClient.GenericExecuteSpec exec = databaseClient.sql(sql.toString());
        DatabaseClient.GenericExecuteSpec count = databaseClient.sql(countSql);

        if (status != null && !status.isBlank()) { exec = exec.bind("status", status); count = count.bind("status", status); }
        if (cajeroId != null) { exec = exec.bind("cajeroId", cajeroId); count = count.bind("cajeroId", cajeroId); }
        if (patientId != null) { exec = exec.bind("patientId", patientId); count = count.bind("patientId", patientId); }
        if (search != null && !search.isBlank()) { exec = exec.bind("search", "%" + search + "%"); count = count.bind("search", "%" + search + "%"); }
        exec = exec.bind("size", size).bind("offset", (long) page * size);

        Flux<RecaudoResponse> content = exec.map((row, meta) -> mapRowToRecaudo(row)).all()
                .flatMap(this::buildResponse);
        Mono<Long> total = count.map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L);

        return Mono.zip(content.collectList(), total)
                .map(t -> {
                    int totalPages = (int) Math.ceil((double) t.getT2() / size);
                    return new PagedResponse<>(t.getT1(), page, size, t.getT2(), totalPages, page >= totalPages - 1);
                });
    }

    public Mono<DashboardStatsResponse> getDashboardStats() {
        return Mono.zip(
                recaudoRepository.sumTodayConfirmed().defaultIfEmpty(BigDecimal.ZERO),
                recaudoRepository.countTodayConfirmed().defaultIfEmpty(0L),
                recaudoRepository.countTodayAnulados().defaultIfEmpty(0L)
        ).map(t -> {
            BigDecimal total = t.getT1();
            long txns = t.getT2();
            BigDecimal promedio = txns > 0
                    ? total.divide(BigDecimal.valueOf(txns), 0, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return DashboardStatsResponse.builder()
                    .totalRecaudadoHoy(total)
                    .transaccionesHoy(txns)
                    .promedioPorTransaccion(promedio)
                    .anulacionesHoy(t.getT3())
                    .build();
        });
    }

    // ─── Acumulado anual de copago (RN-06/07) ────────────────────────────────

    private Mono<Void> actualizarAcumuladoCopago(Recaudo recaudo) {
        if (!"copago".equalsIgnoreCase(recaudo.getTipoCobro())
                || recaudo.getValorTotal() == null
                || recaudo.getValorTotal().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.empty();
        }
        int anio = (recaudo.getFechaAtencion() != null ? recaudo.getFechaAtencion() : LocalDate.now()).getYear();
        String upsert = "INSERT INTO acumulado_copago_anual (patient_id, anio, total_copago, updated_at) " +
                "VALUES (:patientId, :anio, :valor, NOW()) " +
                "ON CONFLICT (patient_id, anio) DO UPDATE " +
                "SET total_copago = acumulado_copago_anual.total_copago + :valor, updated_at = NOW()";
        return databaseClient.sql(upsert)
                .bind("patientId", recaudo.getPatientId())
                .bind("anio", anio)
                .bind("valor", recaudo.getValorTotal())
                .fetch().rowsUpdated().then();
    }

    private Mono<Void> revertirAcumuladoCopago(Recaudo recaudo) {
        if (!"copago".equalsIgnoreCase(recaudo.getTipoCobro())
                || recaudo.getValorTotal() == null
                || recaudo.getValorTotal().compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.empty();
        }
        int anio = (recaudo.getFechaAtencion() != null ? recaudo.getFechaAtencion() : LocalDate.now()).getYear();
        String update = "UPDATE acumulado_copago_anual " +
                "SET total_copago = GREATEST(0, total_copago - :valor), updated_at = NOW() " +
                "WHERE patient_id = :patientId AND anio = :anio";
        return databaseClient.sql(update)
                .bind("patientId", recaudo.getPatientId())
                .bind("anio", anio)
                .bind("valor", recaudo.getValorTotal())
                .fetch().rowsUpdated().then();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Mono<String> generateNumeroComprobante() {
        return recaudoRepository.nextSequence()
                .map(seq -> {
                    String yyyymm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
                    return String.format("REC-%s-%06d", yyyymm, seq);
                });
    }

    private Mono<Void> saveItems(Long recaudoId, List<RecaudoItemRequest> items) {
        List<RecaudoItem> entities = items.stream().map(i -> RecaudoItem.builder()
                .recaudoId(recaudoId)
                .medicalOrderId(i.getMedicalOrderId())
                .appointmentId(i.getAppointmentId())
                .cupsCode(i.getCupsCode())
                .cupsDescription(i.getCupsDescription())
                .serviceType(i.getServiceType())
                .ambito(i.getAmbito() != null ? i.getAmbito() : "AMBULATORIO")
                .professionalId(i.getProfessionalId())
                .professionalName(i.getProfessionalName())
                .serviceDate(i.getServiceDate())
                .baseTariff(i.getBaseTariff() != null ? i.getBaseTariff() : BigDecimal.ZERO)
                .descuentoConvenio(i.getDescuentoConvenio() != null ? i.getDescuentoConvenio() : BigDecimal.ZERO)
                .cuotaModeradora(i.getCuotaModeradora() != null ? i.getCuotaModeradora() : BigDecimal.ZERO)
                .copago(i.getCopago() != null ? i.getCopago() : BigDecimal.ZERO)
                .valorCobrado(i.getValorCobrado() != null ? i.getValorCobrado() : BigDecimal.ZERO)
                .exento(i.getExento() != null ? i.getExento() : false)
                .createdAt(LocalDateTime.now())
                .build()).collect(Collectors.toList());
        return itemRepository.saveAll(entities).then();
    }

    private Mono<Void> markOrdersInRecaudo(List<RecaudoItemRequest> items) {
        return Flux.fromIterable(items)
                .filter(i -> i.getMedicalOrderId() != null)
                .flatMap(i -> orderRepository.findById(i.getMedicalOrderId())
                        .flatMap(o -> {
                            o.setStatus("EN_RECAUDO");
                            o.setUpdatedAt(LocalDateTime.now());
                            return orderRepository.save(o);
                        }))
                .then();
    }

    private Mono<Void> markOrdersRecaudado(Long recaudoId) {
        return itemRepository.findByRecaudoId(recaudoId)
                .filter(i -> i.getMedicalOrderId() != null)
                .flatMap(i -> orderRepository.findById(i.getMedicalOrderId())
                        .flatMap(o -> {
                            o.setStatus("RECAUDADO");
                            o.setUpdatedAt(LocalDateTime.now());
                            return orderRepository.save(o);
                        }))
                .then();
    }

    private Mono<Void> revertOrdersToPending(Long recaudoId) {
        return itemRepository.findByRecaudoId(recaudoId)
                .filter(i -> i.getMedicalOrderId() != null)
                .flatMap(i -> orderRepository.findById(i.getMedicalOrderId())
                        .flatMap(o -> {
                            o.setStatus("PENDIENTE_RECAUDO");
                            o.setUpdatedAt(LocalDateTime.now());
                            return orderRepository.save(o);
                        }))
                .then();
    }

    private Mono<RecaudoResponse> buildResponse(Recaudo r) {
        return itemRepository.findByRecaudoId(r.getId())
                .map(this::itemToResponse)
                .collectList()
                .map(items -> RecaudoResponse.builder()
                        .id(r.getId())
                        .numeroComprobante(r.getNumeroComprobante())
                        .eventoId(r.getEventoId())
                        .episodioId(r.getEpisodioId())
                        .corrigeComprobanteId(r.getCorrigeComprobanteId())
                        .contratoVersion(r.getContratoVersion())
                        .patientId(r.getPatientId())
                        .cajeroId(r.getCajeroId())
                        .sedeId(r.getSedeId())
                        .epsId(r.getEpsId())
                        .epsNombre(r.getEpsNombre())
                        .regimen(r.getRegimen())
                        .rolAfiliado(r.getRolAfiliado())
                        .categoriaIbc(r.getCategoriaIbc())
                        .tipoServicio(r.getTipoServicio())
                        .numeroAutorizacion(r.getNumeroAutorizacion())
                        .esPyd(r.getEsPyd())
                        .exencionCodigo(r.getExencionCodigo())
                        .tipoCobro(r.getTipoCobro())
                        .status(r.getStatus())
                        .medioPago(r.getMedioPago())
                        .valorTotal(r.getValorTotal())
                        .valorRecibido(r.getValorRecibido())
                        .cambio(r.getCambio())
                        .comprobanteInmutable(r.getComprobanteInmutable())
                        .observaciones(r.getObservaciones())
                        .fechaAtencion(r.getFechaAtencion())
                        .confirmadoAt(r.getConfirmadoAt())
                        .createdAt(r.getCreatedAt())
                        .items(items)
                        .build());
    }

    private RecaudoItemResponse itemToResponse(RecaudoItem i) {
        return RecaudoItemResponse.builder()
                .id(i.getId())
                .recaudoId(i.getRecaudoId())
                .medicalOrderId(i.getMedicalOrderId())
                .appointmentId(i.getAppointmentId())
                .cupsCode(i.getCupsCode())
                .cupsDescription(i.getCupsDescription())
                .serviceType(i.getServiceType())
                .ambito(i.getAmbito())
                .professionalId(i.getProfessionalId())
                .professionalName(i.getProfessionalName())
                .serviceDate(i.getServiceDate())
                .baseTariff(i.getBaseTariff())
                .descuentoConvenio(i.getDescuentoConvenio())
                .cuotaModeradora(i.getCuotaModeradora())
                .copago(i.getCopago())
                .valorCobrado(i.getValorCobrado())
                .exento(i.getExento())
                .build();
    }

    private Recaudo mapRowToRecaudo(io.r2dbc.spi.Row row) {
        return Recaudo.builder()
                .id(row.get("id", Long.class))
                .numeroComprobante(row.get("numero_comprobante", String.class))
                .episodioId(row.get("episodio_id", String.class))
                .patientId(row.get("patient_id", Long.class))
                .cajeroId(row.get("cajero_id", Long.class))
                .sedeId(row.get("sede_id", Long.class))
                .epsId(row.get("eps_id", String.class))
                .epsNombre(row.get("eps_nombre", String.class))
                .regimen(row.get("regimen", String.class))
                .rolAfiliado(row.get("rol_afiliado", String.class))
                .tipoCobro(row.get("tipo_cobro", String.class))
                .status(row.get("status", String.class))
                .medioPago(row.get("medio_pago", String.class))
                .valorTotal(row.get("valor_total", BigDecimal.class))
                .valorRecibido(row.get("valor_recibido", BigDecimal.class))
                .cambio(row.get("cambio", BigDecimal.class))
                .observaciones(row.get("observaciones", String.class))
                .confirmadoAt(row.get("confirmado_at", LocalDateTime.class))
                .createdAt(row.get("created_at", LocalDateTime.class))
                .build();
    }
}
