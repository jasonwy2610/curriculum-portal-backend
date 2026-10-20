package vn.edu.huflit.portal.course.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.changelog.dto.ChangeLogResponse;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.course.dto.CourseResponse;
import vn.edu.huflit.portal.course.dto.CreateCourseRequest;
import vn.edu.huflit.portal.course.dto.UpdateCourseRequest;
import vn.edu.huflit.portal.course.service.CourseService;
import vn.edu.huflit.portal.dto.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping({"/api/courses", "/api/v1/admin/courses"})
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final ChangeLogService changeLogService;

    /**
     * F19: Tìm kiếm & lọc danh sách học phần.
     * Hỗ trợ query params: keyword, department, status.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<CourseResponse>>> getAllCourses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String status
    ) {
        List<CourseResponse> courses = courseService.getAllCourses(keyword, department, status);
        return ResponseEntity.ok(ApiResponse.ok(courses));
    }

    /**
     * F20: Xem chi tiết học phần theo ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CourseResponse>> getCourseById(@PathVariable String id) {
        CourseResponse course = courseService.getCourseById(id);
        return ResponseEntity.ok(ApiResponse.ok(course));
    }

    /**
     * F20: Xem chi tiết học phần theo mã học phần (business key).
     */
    @GetMapping("/code/{courseCode}")
    public ResponseEntity<ApiResponse<CourseResponse>> getCourseByCourseCode(@PathVariable String courseCode) {
        CourseResponse course = courseService.getCourseByCourseCode(courseCode);
        return ResponseEntity.ok(ApiResponse.ok(course));
    }

    /**
     * F18: Tạo mới học phần.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CourseResponse>> createCourse(@Valid @RequestBody CreateCourseRequest request) {
        CourseResponse created = courseService.createCourse(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Tạo học phần thành công", created));
    }

    /**
     * F18: Cập nhật thông tin học phần (courseCode bất biến, không thay đổi).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CourseResponse>> updateCourse(
            @PathVariable String id,
            @Valid @RequestBody UpdateCourseRequest request
    ) {
        CourseResponse updated = courseService.updateCourse(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật học phần thành công", updated));
    }

    /**
     * F18: Xóa mềm học phần (chuyển status sang INACTIVE).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CourseResponse>> deleteCourse(@PathVariable String id) {
        CourseResponse deleted = courseService.deleteCourse(id);
        return ResponseEntity.ok(ApiResponse.ok("Xóa mềm học phần thành công", deleted));
    }

    /**
     * F42: Lấy lịch sử thay đổi của học phần.
     */
    @GetMapping("/{id}/change-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ChangeLogResponse>>> getCourseChangeLogs(@PathVariable String id) {
        List<ChangeLogResponse> logs = changeLogService.getCourseChangeLogs(id);
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }
}
