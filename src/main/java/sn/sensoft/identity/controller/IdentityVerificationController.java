package sn.sensoft.identity.controller;

import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.service.IdentityVerificationService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

@Controller("/api/v1/verification")
public class IdentityVerificationController {

    @Inject
    private IdentityVerificationService verificationService;

    @Post(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<VerificationResultDto> uploadAndVerify(
            @Part("identityDocument") CompletedFileUpload identityDocument,
            @Part("userPhoto") CompletedFileUpload userPhoto,
            @Part("userIdentifier") String userIdentifier) {

        try {
            VerificationResultDto result = verificationService.processVerification(
                    userIdentifier, identityDocument, userPhoto
            );

            if ("FAILED".equals(result.getStatus())) {
                return HttpResponse.badRequest(result);
            }

            return HttpResponse.ok(result);
        } catch (Exception e) {
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    @Get("/status/{requestId}")
    public HttpResponse<VerificationResultDto> getVerificationStatus(UUID requestId) {
        return verificationService.getVerificationResult(requestId)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Get("/history/{userIdentifier}")
    public HttpResponse<List<VerificationResultDto>> getUserVerificationHistory(String userIdentifier) {
        List<VerificationResultDto> history = verificationService.getUserHistory(userIdentifier);
        return HttpResponse.ok(history);
    }

    @Get("/health")
    public HttpResponse<String> health() {
        return HttpResponse.ok("Identity Verification Service is running");
    }
}