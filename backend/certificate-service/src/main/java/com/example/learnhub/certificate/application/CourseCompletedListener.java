package com.example.learnhub.certificate.application;

import com.example.learnhub.certificate.domain.Certificate;
import com.example.learnhub.certificate.domain.OutboxEvent;
import com.example.learnhub.certificate.domain.ProcessedEvent;
import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import com.example.learnhub.certificate.infrastructure.CourseLookupClient;
import com.example.learnhub.certificate.infrastructure.CourseSummaryResponse;
import com.example.learnhub.certificate.infrastructure.OutboxEventRepository;
import com.example.learnhub.certificate.infrastructure.ProcessedEventRepository;
import com.example.learnhub.certificate.infrastructure.RabbitTopologyConfig;
import com.example.learnhub.events.CertificateIssuedPayload;
import com.example.learnhub.events.CourseCompletedPayload;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.EventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code course.completed} (research.md R6). Idempotent by {@code eventId}: a replayed event
 * is acknowledged and otherwise ignored (FR-010 scenario 2). A course-service lookup failure throws
 * (research.md R5), letting the message retry rather than issuing a titleless certificate.
 */
@Component
public class CourseCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(CourseCompletedListener.class);
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final CertificateRepository certificates;
    private final ProcessedEventRepository processedEvents;
    private final OutboxEventRepository outboxEvents;
    private final CourseLookupClient courseLookupClient;
    private final CertificateCodeGenerator codeGenerator;
    private final ObjectMapper mapper = EventMapper.create();

    public CourseCompletedListener(CertificateRepository certificates, ProcessedEventRepository processedEvents,
            OutboxEventRepository outboxEvents, CourseLookupClient courseLookupClient,
            CertificateCodeGenerator codeGenerator) {
        this.certificates = certificates;
        this.processedEvents = processedEvents;
        this.outboxEvents = outboxEvents;
        this.courseLookupClient = courseLookupClient;
        this.codeGenerator = codeGenerator;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE, containerFactory = RabbitTopologyConfig.LISTENER_CONTAINER_FACTORY)
    @Transactional
    public void onCourseCompleted(EventEnvelope envelope) {
        UUID eventId = envelope.eventId();
        if (processedEvents.existsById(eventId)) {
            log.info("course.completed {} already processed; ignoring replay", eventId);
            return;
        }

        CourseCompletedPayload payload;
        try {
            payload = mapper.treeToValue(envelope.payload(), CourseCompletedPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed course.completed payload: " + eventId, e);
        }

        if (certificates.existsByUserIdAndCourseId(payload.userId(), payload.courseId())) {
            // Already certified (e.g. a prior delivery got this far but the processed-event row never
            // committed); still record this event as processed so it is never retried again.
            processedEvents.save(new ProcessedEvent(eventId, Instant.now()));
            return;
        }

        // research.md R5: throws (via CourseLookupClientFallbackFactory) if course-service cannot be
        // reached, letting this message retry rather than issuing a certificate with no real title.
        CourseSummaryResponse course = courseLookupClient.summary(payload.courseId());

        Instant now = Instant.now();
        Certificate certificate = new Certificate(UUID.randomUUID(), payload.userId(), payload.courseId(),
                generateUniqueCode(), course.title(), now);
        certificates.save(certificate);
        processedEvents.save(new ProcessedEvent(eventId, now));

        CertificateIssuedPayload issuedPayload = new CertificateIssuedPayload(certificate.getId(),
                certificate.getUserId(), certificate.getCourseId(), certificate.getVerificationCode(), now);
        String json;
        try {
            json = mapper.writeValueAsString(issuedPayload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize certificate.issued payload", e);
        }
        outboxEvents.save(new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), certificate.getId(), json, now));
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String candidate = codeGenerator.generate();
            if (!certificates.existsByVerificationCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique verification code after "
                + MAX_CODE_ATTEMPTS + " attempts");
    }
}
