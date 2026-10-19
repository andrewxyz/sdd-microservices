package com.example.learnhub.certificate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.certificate.domain.Certificate;
import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import com.example.learnhub.certificate.support.AbstractCertificateServiceIT;
import com.example.learnhub.common.identity.IdentityHeaders;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** US5 (FR-013, FR-014): a student lists only their own certificates. */
class CertificateHistoryIT extends AbstractCertificateServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CertificateRepository certificates;

    @Test
    void returnsOnlyTheCallersOwnCertificatesPaginated() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        certificates.save(new Certificate(UUID.randomUUID(), userId, UUID.randomUUID(), "MINE000000000001",
                "My Course One", Instant.now()));
        certificates.save(new Certificate(UUID.randomUUID(), otherUserId, UUID.randomUUID(), "THEIRSCERT000001",
                "Someone Elses Course", Instant.now()));

        mockMvc.perform(get("/api/v1/certificates/mine")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseTitle").value("My Course One"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void aSecondUsersListNeverIncludesTheFirstUsersCertificates() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        certificates.save(new Certificate(UUID.randomUUID(), userId, UUID.randomUUID(), "ISOLATE000000001",
                "Isolation Course", Instant.now()));

        mockMvc.perform(get("/api/v1/certificates/mine")
                        .header(IdentityHeaders.USER_ID, otherUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0));
    }
}
