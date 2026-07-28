package com.aegis.merged.kyc;

/**
 * The seam between this codebase and a real identity-verification vendor (Persona, Alloy,
 * Socure). Nothing in this system is legally allowed to attest to a real customer's identity
 * on its own — that requires a licensed bureau. This interface exists so the REST of the app
 * (the tool that requests re-verification, the audit trail, the approval flow) is already
 * built against the real shape of that integration; swapping {@link MockKycProvider} for a
 * real vendor SDK client is the only change needed when that vendor contract exists.
 */
public interface KycProvider {

    enum Outcome { VERIFIED, NEEDS_MORE_INFO, REJECTED, PENDING }

    record VerificationResult(String referenceId, Outcome outcome, String detail) {
    }

    /** Kicks off (or checks) an identity-verification session for this customer. A real
        implementation calls out to the vendor's API and very likely returns PENDING while a
        human or ML review completes asynchronously — never a same-request VERIFIED for
        anything beyond the lowest KYC tier. */
    VerificationResult verify(String tenantId, String userId, String reason);
}
