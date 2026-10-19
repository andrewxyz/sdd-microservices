package com.example.learnhub.notification.application;

import com.example.learnhub.events.EnrollmentCreatedPayload;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.EventMapper;
import com.example.learnhub.notification.domain.Notification;
import com.example.learnhub.notification.domain.NotificationStatus;
import com.example.learnhub.notification.domain.NotificationType;
import com.example.learnhub.notification.infrastructure.AccountLookupClient;
import com.example.learnhub.notification.infrastructure.AccountSummaryResponse;
import com.example.learnhub.notification.infrastructure.CourseLookupClient;
import com.example.learnhub.notification.infrastructure.CourseSummaryResponse;
import com.example.learnhub.notification.infrastructure.EmailSender;
import com.example.learnhub.notification.infrastructure.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a consumed {@code enrollment.created} event into exactly one "you're enrolled" email
 * (research.md R7's two-phase write; FR-019, FR-022). Invoked by {@link NotificationDispatchListener} —
 * see that class's Javadoc for why this is not itself a {@code @RabbitListener}.
 */
@Component
public class EnrollmentCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentCreatedListener.class);

    private final NotificationRepository notifications;
    private final AccountLookupClient accountLookupClient;
    private final CourseLookupClient courseLookupClient;
    private final EmailSender emailSender;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper = EventMapper.create();

    public EnrollmentCreatedListener(NotificationRepository notifications, AccountLookupClient accountLookupClient,
            CourseLookupClient courseLookupClient, EmailSender emailSender,
            PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.accountLookupClient = accountLookupClient;
        this.courseLookupClient = courseLookupClient;
        this.emailSender = emailSender;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void handle(EventEnvelope envelope) {
        EnrollmentCreatedPayload payload;
        try {
            payload = mapper.treeToValue(envelope.payload(), EnrollmentCreatedPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed enrollment.created payload: " + envelope.eventId(), e);
        }

        Notification notification = findOrInsertPending(envelope.eventId(), payload.userId());
        if (notification.getStatus() == NotificationStatus.SENT) {
            log.info("enrollment.created {} already sent; ignoring replay", envelope.eventId());
            return;
        }

        // Phase two, deliberately outside any transaction (research.md R7): any failure here throws,
        // leaving the PENDING row in place for the next retry attempt to resume from.
        AccountSummaryResponse account = accountLookupClient.summary(payload.userId());
        CourseSummaryResponse course = courseLookupClient.summary(payload.courseId());
        emailSender.send(notification.getId(), account.email(), "You're enrolled in " + course.title(),
                "You have successfully enrolled in \"" + course.title() + "\".");

        transactionTemplate.executeWithoutResult(status -> {
            notification.markSent();
            notifications.save(notification);
        });
    }

    private Notification findOrInsertPending(UUID eventId, UUID userId) {
        return transactionTemplate.execute(status -> notifications.findByEventId(eventId)
                .orElseGet(() -> notifications.save(new Notification(UUID.randomUUID(), eventId, userId,
                        NotificationType.ENROLLMENT, Instant.now()))));
    }
}
