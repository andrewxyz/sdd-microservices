package com.example.learnhub.events;

/**
 * Constants of the single topic exchange every LearnHub event flows through, plus the routing-key
 * constants for 005-progress-certificates-notifications's three new event types.
 *
 * <p>Earlier routing keys (e.g. {@code payment.succeeded}) are deliberately not centralized here — see
 * {@link RoutingKeys}'s own Javadoc on why a shared library should stay free of domain knowledge
 * (constitution Principle II). The three constants below are a deliberate, explicit exception recorded
 * in specs/005-progress-certificates-notifications/plan.md's Constitution Check: a routing key is a
 * fixed string, not behavior, and the services on both ends of each of these three events needed one
 * shared, unambiguous name for it.
 */
public final class LearningEvents {

    /** The one topic exchange for the whole platform. */
    public static final String EXCHANGE = "learning.events";

    /** Published by enrollment-service when every lesson of a course is first completed. */
    public static final String COURSE_COMPLETED = "course.completed";

    /** Published by certificate-service when a certificate is issued. */
    public static final String CERTIFICATE_ISSUED = "certificate.issued";

    /** Published by enrollment-service when an enrollment is first created. */
    public static final String ENROLLMENT_CREATED = "enrollment.created";

    private LearningEvents() {
    }
}
