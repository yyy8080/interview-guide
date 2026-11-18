package interview.guide.modules.recruitment.model;

import java.time.Instant;

public record CandidateLoginResponse(String token, Instant expiresAt, CandidateProfileDTO candidate) {
}
