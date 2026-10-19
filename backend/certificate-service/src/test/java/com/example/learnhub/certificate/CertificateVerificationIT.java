package com.example.learnhub.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.certificate.domain.Certificate;
import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import com.example.learnhub.certificate.support.AbstractCertificateServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** US4 (FR-015 to FR-018): anyone can verify a certificate by code, without learning who holds it. */
class CertificateVerificationIT extends AbstractCertificateServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CertificateRepository certificates;

    @Test
    void aRealCodeIsReportedValidWithCourseTitleAndDateButNoHolderInformation() throws Exception {
        Certificate certificate = certificates.save(new Certificate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "ABCD2345EFGH6789", "Verification Test Course", Instant.now()));

        mockMvc.perform(get("/api/v1/certificates/verify/" + certificate.getVerificationCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.courseTitle").value("Verification Test Course"))
                .andExpect(jsonPath("$.issuedAt").exists())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContainIgnoringCase("userId"));
    }

    @Test
    void anUnknownCodeReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/certificates/verify/DOESNOTEXIST0001"))
                .andExpect(status().isNotFound());
    }
}
