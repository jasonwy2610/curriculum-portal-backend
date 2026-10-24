package vn.edu.huflit.portal.academic.controller;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.response.AcademicRecordResponse;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.academic.service.AcademicRecordService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/student/academic-records")
@RequiredArgsConstructor
public class AcademicRecordController {

    private final AcademicRecordService academicRecordService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AcademicRecordResponse>>> getMyAcademicRecords(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        academicRecordService.getMyAcademicRecords(user.getId())
                )
        );
    }

    @GetMapping("/{courseCode}")
    public ResponseEntity<ApiResponse<AcademicRecordResponse>> getMyAcademicRecord(
            @AuthenticationPrincipal User user,
            @PathVariable String courseCode,
            @RequestParam String curriculumId
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        academicRecordService.getMyAcademicRecord(
                                user.getId(),
                                courseCode,
                                parseObjectId(curriculumId, "curriculumId")
                        )
                )
        );
    }

    private ObjectId parseObjectId(String value, String fieldName) {
        if (!ObjectId.isValid(value)) {
            throw new RuntimeException(fieldName + " không hợp lệ");
        }
        return new ObjectId(value);
    }
}
