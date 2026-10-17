package com.example.learnhub.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * FR-027: an envelope published to the real broker on {@code learning.events} comes back unchanged.
 *
 * <p>Constitution Principle VI: integration tests use Testcontainers, never an in-memory stand-in.
 * Queue and dead-letter layouts are out of scope for this feature, so this declares a throwaway queue.
 */
@Testcontainers
class EnvelopeBrokerRoundTripIT {

    @Container
    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3-management"));

    @Test
    @DisplayName("envelope published with its type as routing key is consumed unchanged")
    void envelopeSurvivesTheBroker() throws Exception {
        ObjectMapper mapper = EventMapper.create();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("paymentId", "pay_42");
        payload.put("amountCents", 4999);

        EventEnvelope sent = new EventEnvelope(
                UUID.randomUUID(),
                "payment.succeeded",
                1,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "6f1c2b7e-9d3a-4c55-8a10-2f4e7d9b1a33",
                payload);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(RABBIT.getAmqpUrl());

        BlockingQueue<String> received = new ArrayBlockingQueue<>(1);

        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(LearningEvents.EXCHANGE, BuiltinExchangeType.TOPIC, true);
            String queue = channel.queueDeclare().getQueue();
            channel.queueBind(queue, LearningEvents.EXCHANGE, "payment.*");

            DeliverCallback onMessage =
                    (tag, delivery) -> received.offer(new String(delivery.getBody(), StandardCharsets.UTF_8));
            channel.basicConsume(queue, true, onMessage, tag -> { });

            channel.basicPublish(
                    LearningEvents.EXCHANGE,
                    sent.type(),
                    null,
                    mapper.writeValueAsBytes(sent));

            String body = received.poll(10, TimeUnit.SECONDS);
            assertThat(body).as("message delivered within 10s").isNotNull();

            EventEnvelope back = mapper.readValue(body, EventEnvelope.class);
            assertThat(back).isEqualTo(sent);
            assertThat(back.payload().get("paymentId").asText()).isEqualTo("pay_42");
        }
    }
}
