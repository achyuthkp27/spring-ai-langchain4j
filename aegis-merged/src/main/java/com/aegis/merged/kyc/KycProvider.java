package com.aegis.merged.kyc;

public interface KycProvider {

    enum Outcome { VERIFIED, NEEDS_MORE_INFO, REJECTED, PENDING }

    record VerificationResult(String referenceId, Outcome outcome, String detail) {
    }

    VerificationResult verify(String tenantId, String userId, String reason);
}
