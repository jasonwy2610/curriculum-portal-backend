package vn.edu.huflit.portal.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.request.CreateCourseRequest;
import vn.edu.huflit.portal.dto.request.UpdateCourseRequest;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.dto.response.CourseResponse;
import vn.edu.huflit.portal.service.CourseService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/courses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class CourseController {

    private final CourseService courseService;

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
    public ResponseEntity<ApiResponse<CourseResponse>> createCourse(@Valid @RequestBody CreateCourseRequest request) {
        CourseResponse created = courseService.createCourse(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Tạo học phần thành công", created));
    }

    /**
     * F18: Cập nhật thông tin học phần (courseCode bất biến, không thay đổi).
     */
    @PutMapping("/{id}")
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
    public ResponseEntity<ApiResponse<CourseResponse>> deleteCourse(@PathVariable String id) {
        CourseResponse deleted = courseService.deleteCourse(id);
        return ResponseEntity.ok(ApiResponse.ok("Xóa mềm học phần thành công", deleted));
    }
}
