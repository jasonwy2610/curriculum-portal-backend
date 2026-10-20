package vn.edu.huflit.portal.changelog.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.changelog.dto.ChangeLogResponse;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.dto.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping({"/api/change-logs", "/api/v1/admin/change-logs"})
@RequiredArgsConstructor
public class ChangeLogController {

    private final ChangeLogService changeLogService;

    /**
     * Tra cứu lịch sử thay đổi (Audit trail) với các bộ lọc:
     * - entityType: "Curriculum" | "Course"
     * - entityId: ID thực thể
     * - action: "CREATE" | "UPDATE" | "CLONE" | "DELETE"
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ChangeLogResponse>>> getChangeLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String action
    ) {
        List<ChangeLogResponse> logs = changeLogService.getChangeLogs(entityType, entityId, action);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }

    /**
     * F41: Lấy toàn bộ lịch sử thay đổi của một chương trình đào tạo cụ thể.
     */
    @GetMapping("/curriculum/{curriculumId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ChangeLogResponse>>> getCurriculumChangeLogs(
            @PathVariable String curriculumId
    ) {
        List<ChangeLogResponse> logs = changeLogService.getCurriculumChangeLogs(curriculumId);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }

    /**
     * F42: Lấy toàn bộ lịch sử thay đổi của một học phần cụ thể.
     */
    @GetMapping("/course/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ChangeLogResponse>>> getCourseChangeLogs(
            @PathVariable String courseId
    ) {
        List<ChangeLogResponse> logs = changeLogService.getCourseChangeLogs(courseId);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }
}
