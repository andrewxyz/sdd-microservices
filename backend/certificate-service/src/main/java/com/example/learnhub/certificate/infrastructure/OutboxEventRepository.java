package com.example.learnhub.certificate.infrastructure;

import com.example.learnhub.certificate.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByPublishedAtIsNull();
}
