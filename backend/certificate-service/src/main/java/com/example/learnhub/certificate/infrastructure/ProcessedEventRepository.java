package com.example.learnhub.certificate.infrastructure;

import com.example.learnhub.certificate.domain.ProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
