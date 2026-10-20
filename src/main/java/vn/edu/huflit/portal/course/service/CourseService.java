package vn.edu.huflit.portal.course.service;

import vn.edu.huflit.portal.course.dto.CreateCourseRequest;
import vn.edu.huflit.portal.course.dto.UpdateCourseRequest;
import vn.edu.huflit.portal.course.dto.CourseResponse;

import java.util.List;

public interface CourseService {

    // F19: Tìm kiếm & lọc danh sách học phần
    List<CourseResponse> getAllCourses(String keyword, String department, String status);

    // F20: Xem chi tiết học phần theo ID
    CourseResponse getCourseById(String id);

    // F20: Xem chi tiết học phần theo mã học phần (business key)
    CourseResponse getCourseByCourseCode(String courseCode);

    // F18: Tạo mới học phần
    CourseResponse createCourse(CreateCourseRequest request);

    // F18: Cập nhật học phần (không cho phép đổi courseCode)
    CourseResponse updateCourse(String id, UpdateCourseRequest request);

    // F18: Xóa mềm học phần (chuyển status sang INACTIVE)
    CourseResponse deleteCourse(String id);
}
