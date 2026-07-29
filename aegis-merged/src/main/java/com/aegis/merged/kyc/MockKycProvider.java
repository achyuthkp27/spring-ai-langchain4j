package com.aegis.merged.kyc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class MockKycProvider implements KycProvider {

    private static final Logger log = LoggerFactory.getLogger(MockKycProvider.class);
    private final AtomicLong seq = new AtomicLong(1000);

    @Override
    public VerificationResult verify(String tenantId, String userId, String reason) {
        String ref = "KYC-MOCK-" + seq.incrementAndGet();
        log.warn("kyc.mock.invoked tenant={} user={} reason={} ref={} — NOT a real identity check, "
                + "wire a licensed vendor (Persona/Alloy/Socure) before this leaves demo use", tenantId, userId, reason, ref);
        return new VerificationResult(ref, Outcome.PENDING,
                "Submitted for review — this demo has no real identity bureau behind it.");
    }
}
