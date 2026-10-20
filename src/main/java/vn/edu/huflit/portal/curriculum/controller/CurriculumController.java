package vn.edu.huflit.portal.curriculum.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.changelog.dto.ChangeLogResponse;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.curriculum.dto.*;
import vn.edu.huflit.portal.curriculum.service.CurriculumService;
import vn.edu.huflit.portal.dto.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping({"/api/curriculums", "/api/v1/admin/curriculums"})
@RequiredArgsConstructor
public class CurriculumController {

    private final CurriculumService curriculumService;
    private final ChangeLogService changeLogService;

    /**
     * F04 & F39: Danh sách & tìm kiếm nâng cao CTĐT.
     * Cho phép cả ADMIN và STUDENT truy vấn.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<CurriculumResponse>>> getAllCurriculums(
            @RequestParam(required = false) String majorId,
            @RequestParam(required = false) String cohort,
            @RequestParam(required = false) Integer appliedYear,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String courseCode
    ) {
        List<CurriculumResponse> list = curriculumService.getAllCurriculums(majorId, cohort, appliedYear, status, keyword, courseCode);
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    /**
     * F08: Xem chi tiết CTĐT theo ID.
     * Cho phép cả ADMIN và STUDENT truy vấn.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CurriculumResponse>> getCurriculumById(@PathVariable String id) {
        CurriculumResponse curriculum = curriculumService.getCurriculumById(id);
        return ResponseEntity.ok(ApiResponse.ok(curriculum));
    }

    /**
     * F08: Xem chi tiết CTĐT theo mã CTĐT (business key).
     * Cho phép cả ADMIN và STUDENT truy vấn.
     */
    @GetMapping("/code/{curriculumCode}")
    public ResponseEntity<ApiResponse<CurriculumResponse>> getCurriculumByCode(@PathVariable String curriculumCode) {
        CurriculumResponse curriculum = curriculumService.getCurriculumByCode(curriculumCode);
        return ResponseEntity.ok(ApiResponse.ok(curriculum));
    }

    /**
     * F05: Tạo mới CTĐT.
     * Chỉ ADMIN mới có quyền tạo (BR14).
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> createCurriculum(@Valid @RequestBody CreateCurriculumRequest request) {
        CurriculumResponse created = curriculumService.createCurriculum(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Tạo chương trình đào tạo thành công", created));
    }

    /**
     * F06: Chỉnh sửa thông tin CTĐT.
     * Chỉ ADMIN mới có quyền sửa (BR14).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> updateCurriculum(
            @PathVariable String id,
            @Valid @RequestBody UpdateCurriculumRequest request
    ) {
        CurriculumResponse updated = curriculumService.updateCurriculum(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật chương trình đào tạo thành công", updated));
    }

    /**
     * F07: Deep Clone CTĐT.
     * Tạo một bản sao độc lập hoàn toàn cấu trúc CTĐT sang mã/khóa mới (BR03).
     * Chỉ ADMIN mới có quyền thực hiện (BR14).
     */
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> cloneCurriculum(
            @PathVariable String id,
            @RequestBody CloneCurriculumRequest request
    ) {
        CurriculumResponse cloned = curriculumService.cloneCurriculum(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Sao chép (clone) chương trình đào tạo thành công", cloned));
    }

    /**
     * F09: Soft Delete / Lưu trữ CTĐT.
     * Chuyển trạng thái sang ARCHIVED, không bao giờ xóa cứng khỏi MongoDB (BR02).
     * Chỉ ADMIN mới có quyền thực hiện (BR14).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> deleteCurriculum(@PathVariable String id) {
        CurriculumResponse archived = curriculumService.deleteCurriculum(id);
        return ResponseEntity.ok(ApiResponse.ok("Lưu trữ (xóa mềm) chương trình đào tạo thành công", archived));
    }

    // ====================================================================================
    // F10: QUẢN LÝ NHÓM KIẾN THỨC (KNOWLEDGE GROUPS - BR04)
    // ====================================================================================

    /**
     * F10: Cập nhật / Sắp xếp lại toàn bộ danh sách nhóm kiến thức.
     */
    @PutMapping("/{id}/knowledge-groups")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> replaceKnowledgeGroups(
            @PathVariable String id,
            @Valid @RequestBody List<KnowledgeGroupDto> request
    ) {
        CurriculumResponse updated = curriculumService.replaceKnowledgeGroups(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật danh sách nhóm kiến thức thành công", updated));
    }

    /**
     * F10: Thêm mới một nhóm kiến thức.
     */
    @PostMapping("/{id}/knowledge-groups")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> addKnowledgeGroup(
            @PathVariable String id,
            @Valid @RequestBody KnowledgeGroupDto request
    ) {
        CurriculumResponse updated = curriculumService.addKnowledgeGroup(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Thêm nhóm kiến thức thành công", updated));
    }

    /**
     * F10: Cập nhật thông tin một nhóm kiến thức theo groupCode.
     */
    @PutMapping("/{id}/knowledge-groups/{groupCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> updateKnowledgeGroup(
            @PathVariable String id,
            @PathVariable String groupCode,
            @Valid @RequestBody UpdateKnowledgeGroupRequest request
    ) {
        CurriculumResponse updated = curriculumService.updateKnowledgeGroup(id, groupCode, request);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật nhóm kiến thức '" + groupCode + "' thành công", updated));
    }

    /**
     * F10: Xóa một nhóm kiến thức khỏi CTĐT (kiểm tra ràng buộc không có môn học trực thuộc).
     */
    @DeleteMapping("/{id}/knowledge-groups/{groupCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> deleteKnowledgeGroup(
            @PathVariable String id,
            @PathVariable String groupCode
    ) {
        CurriculumResponse updated = curriculumService.deleteKnowledgeGroup(id, groupCode);
        return ResponseEntity.ok(ApiResponse.ok("Xóa nhóm kiến thức '" + groupCode + "' thành công", updated));
    }

    // ====================================================================================
    // F16 & F17: CHUYÊN NGÀNH & THỜI ĐIỂM CHỌN ĐỊNH HƯỚNG (BR04, BR18)
    // ====================================================================================

    /**
     * F16 & F17: Cấu hình chuyên ngành và thời điểm chọn định hướng (EARLY / LATER - BR18).
     */
    @PutMapping("/{id}/specializations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> configureSpecializations(
            @PathVariable String id,
            @Valid @RequestBody ConfigureSpecializationsRequest request
    ) {
        CurriculumResponse updated = curriculumService.configureSpecializations(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Cấu hình chuyên ngành và thời điểm chọn định hướng thành công", updated));
    }

    /**
     * F16: Thêm mới một chuyên ngành / định hướng vào CTĐT.
     */
    @PostMapping("/{id}/specializations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> addSpecialization(
            @PathVariable String id,
            @Valid @RequestBody SpecializationDto request
    ) {
        CurriculumResponse updated = curriculumService.addSpecialization(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Thêm chuyên ngành thành công", updated));
    }

    /**
     * F16: Xóa một chuyên ngành / định hướng khỏi CTĐT.
     */
    @DeleteMapping("/{id}/specializations/{specCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> deleteSpecialization(
            @PathVariable String id,
            @PathVariable String specCode
    ) {
        CurriculumResponse updated = curriculumService.deleteSpecialization(id, specCode);
        return ResponseEntity.ok(ApiResponse.ok("Xóa chuyên ngành '" + specCode + "' thành công", updated));
    }

    // ====================================================================================
    // F11, F12, F13, F14: QUẢN LÝ HỌC PHẦN TRONG CTĐT (BR04, BR05)
    // ====================================================================================

    /**
     * F11–F14: Gán học phần vào học kỳ của CTĐT với loại môn, nhóm KT, định hướng và tiên quyết.
     */
    @PostMapping("/{id}/courses")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> addCourseToCurriculum(
            @PathVariable String id,
            @Valid @RequestBody AddCurriculumCourseRequest request
    ) {
        CurriculumResponse updated = curriculumService.addCourseToCurriculum(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Thêm học phần vào chương trình đào tạo thành công", updated));
    }

    /**
     * F11–F14: Cập nhật cấu hình học phần trong CTĐT (đổi kỳ, đổi loại, đổi nhóm KT, đổi môn tiên quyết).
     */
    @PutMapping("/{id}/courses/{courseCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> updateCourseInCurriculum(
            @PathVariable String id,
            @PathVariable String courseCode,
            @Valid @RequestBody UpdateCurriculumCourseRequest request
    ) {
        CurriculumResponse updated = curriculumService.updateCourseInCurriculum(id, courseCode, request);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật học phần '" + courseCode + "' trong CTĐT thành công", updated));
    }

    /**
     * F11: Xóa học phần khỏi CTĐT.
     */
    @DeleteMapping("/{id}/courses/{courseCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CurriculumResponse>> deleteCourseFromCurriculum(
            @PathVariable String id,
            @PathVariable String courseCode
    ) {
        CurriculumResponse updated = curriculumService.deleteCourseFromCurriculum(id, courseCode);
        return ResponseEntity.ok(ApiResponse.ok("Xóa học phần '" + courseCode + "' khỏi CTĐT thành công", updated));
    }

    /**
     * F41: Lấy toàn bộ lịch sử thay đổi của một CTĐT cụ thể.
     */
    @GetMapping("/{id}/change-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ChangeLogResponse>>> getCurriculumChangeLogs(@PathVariable String id) {
        List<ChangeLogResponse> logs = changeLogService.getCurriculumChangeLogs(id);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }
}
