package com.example.learnhub.notification.application;

import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.example.learnhub.notification.infrastructure.RabbitTopologyConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The single {@code @RabbitListener} consumer registered on {@code notification.q}. The queue carries two
 * event types via separate bindings (research.md R7), but RabbitMQ hands each message to exactly one
 * subscribed consumer chosen without regard to routing key or content — registering {@link
 * EnrollmentCreatedListener} and {@link CertificateIssuedListener} as two independent listeners on this
 * same queue would make each of them silently lose roughly half of the *other* type's messages the moment
 * one happened to be delivered to it (an implementation finding corrected here, mirroring 005 research.md
 * R9's gateway route-predicate correction). Dispatching on the envelope's own {@code eventType} keeps the
 * queue single-consumer while still giving each listener one handling method apiece (tasks.md T060).
 */
@Component
public class NotificationDispatchListener {

    private final EnrollmentCreatedListener enrollmentCreatedListener;
    private final CertificateIssuedListener certificateIssuedListener;

    public NotificationDispatchListener(EnrollmentCreatedListener enrollmentCreatedListener,
            CertificateIssuedListener certificateIssuedListener) {
        this.enrollmentCreatedListener = enrollmentCreatedListener;
        this.certificateIssuedListener = certificateIssuedListener;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE, containerFactory = RabbitTopologyConfig.LISTENER_CONTAINER_FACTORY)
    public void onMessage(EventEnvelope envelope) {
        if (LearningEvents.ENROLLMENT_CREATED.equals(envelope.type())) {
            enrollmentCreatedListener.handle(envelope);
        } else if (LearningEvents.CERTIFICATE_ISSUED.equals(envelope.type())) {
            certificateIssuedListener.handle(envelope);
        } else {
            throw new IllegalArgumentException("Unexpected event type on notification.q: " + envelope.type());
        }
    }
}
