package com.example.learnhub.auth.infrastructure;

import com.example.learnhub.auth.domain.RefreshTokenRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenRecord, UUID> {

    Optional<RefreshTokenRecord> findByTokenHash(String tokenHash);
}
