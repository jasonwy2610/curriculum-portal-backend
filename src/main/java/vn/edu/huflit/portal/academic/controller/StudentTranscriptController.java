package vn.edu.huflit.portal.academic.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.academic.service.StudentTranscriptService;
import vn.edu.huflit.portal.dto.response.*;

@RestController
@RequiredArgsConstructor
public class StudentTranscriptController {
    private final StudentTranscriptService transcripts;
    @GetMapping("/api/v1/student/academic-records/transcript")
    public ApiResponse<StudentTranscriptResponse> get(@AuthenticationPrincipal User student) {
        return ApiResponse.ok(transcripts.get(student));
    }
}
