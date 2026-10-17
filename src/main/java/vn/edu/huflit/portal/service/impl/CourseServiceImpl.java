package vn.edu.huflit.portal.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.dto.request.CreateCourseRequest;
import vn.edu.huflit.portal.dto.request.UpdateCourseRequest;
import vn.edu.huflit.portal.dto.response.CourseResponse;
import vn.edu.huflit.portal.entity.Course;
import vn.edu.huflit.portal.repository.CourseRepository;
import vn.edu.huflit.portal.service.CourseService;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private final CourseRepository courseRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * F19: Tìm kiếm & lọc danh sách học phần.
     * Hỗ trợ tìm theo keyword (regex trên courseCode + courseName),
     * lọc theo department và status.
     */
    @Override
    public List<CourseResponse> getAllCourses(String keyword, String department, String status) {
        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        // Lọc theo trạng thái (status)
        if (status != null && !status.trim().isEmpty()) {
            criteriaList.add(Criteria.where("status").is(status.trim().toUpperCase()));
        }

        // Lọc theo khoa/bộ môn (department) — khớp chính xác
        if (department != null && !department.trim().isEmpty()) {
            criteriaList.add(Criteria.where("department").regex(Pattern.quote(department.trim()), "i"));
        }

        // Tìm kiếm theo từ khóa (keyword) — regex trên courseCode và courseName
        if (keyword != null && !keyword.trim().isEmpty()) {
            String sanitizedKeyword = Pattern.quote(keyword.trim());
            Criteria keywordCriteria = new Criteria().orOperator(
                    Criteria.where("courseCode").regex(sanitizedKeyword, "i"),
                    Criteria.where("courseName").regex(sanitizedKeyword, "i")
            );
            criteriaList.add(keywordCriteria);
        }

        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }

        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));

        List<Course> courses = mongoTemplate.find(query, Course.class);
        return courses.stream()
                .map(CourseResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * F20: Xem chi tiết học phần theo ID.
     */
    @Override
    public CourseResponse getCourseById(String id) {
        Course course = findCourseEntityById(id);
        return CourseResponse.fromEntity(course);
    }

    /**
     * F20: Xem chi tiết học phần theo mã học phần (business key).
     */
    @Override
    public CourseResponse getCourseByCourseCode(String courseCode) {
        if (courseCode == null || courseCode.trim().isEmpty()) {
            throw new RuntimeException("Mã học phần không được để trống");
        }
        Course course = courseRepository.findByCourseCode(courseCode.trim())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy học phần với mã: " + courseCode));
        return CourseResponse.fromEntity(course);
    }

    /**
     * F18: Tạo mới học phần.
     * Kiểm tra trùng courseCode trước khi tạo.
     */
    @Override
    public CourseResponse createCourse(CreateCourseRequest request) {
        String courseCode = request.getCourseCode().trim();

        // Kiểm tra trùng lặp business key
        if (courseRepository.existsByCourseCode(courseCode)) {
            throw new RuntimeException("Mã học phần '" + courseCode + "' đã tồn tại trong hệ thống");
        }

        // Xác định status mặc định
        String status = (request.getStatus() != null && !request.getStatus().trim().isEmpty())
                ? request.getStatus().trim().toUpperCase()
                : "ACTIVE";

        Course course = Course.builder()
                .courseCode(courseCode)
                .courseName(request.getCourseName().trim())
                .credits(request.getCredits())
                .theoryHours(request.getTheoryHours())
                .practiceHours(request.getPracticeHours())
                .description(request.getDescription())
                .department(request.getDepartment())
                .status(status)
                .build();

        Course saved = courseRepository.save(course);
        return CourseResponse.fromEntity(saved);
    }

    /**
     * F18: Cập nhật thông tin học phần.
     * courseCode là business key bất biến, không cho phép thay đổi.
     */
    @Override
    public CourseResponse updateCourse(String id, UpdateCourseRequest request) {
        Course course = findCourseEntityById(id);

        // Cập nhật các trường cho phép thay đổi
        course.setCourseName(request.getCourseName().trim());
        course.setCredits(request.getCredits());
        course.setTheoryHours(request.getTheoryHours());
        course.setPracticeHours(request.getPracticeHours());

        if (request.getDescription() != null) {
            course.setDescription(request.getDescription());
        }
        if (request.getDepartment() != null) {
            course.setDepartment(request.getDepartment());
        }
        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            course.setStatus(request.getStatus().trim().toUpperCase());
        }

        Course updated = courseRepository.save(course);
        return CourseResponse.fromEntity(updated);
    }

    /**
     * F18: Xóa mềm học phần.
     * Áp dụng Soft Delete theo quy chuẩn Baseline v4.2:
     * chuyển status sang "INACTIVE", KHÔNG xóa document khỏi MongoDB.
     */
    @Override
    public CourseResponse deleteCourse(String id) {
        Course course = findCourseEntityById(id);

        course.setStatus("INACTIVE");
        Course updated = courseRepository.save(course);
        return CourseResponse.fromEntity(updated);
    }

    /**
     * Phương thức nội bộ: tìm Course entity theo ID với kiểm tra ObjectId hợp lệ.
     */
    private Course findCourseEntityById(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new RuntimeException("ID học phần không hợp lệ: " + id);
        }
        return courseRepository.findById(new ObjectId(id))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy học phần với ID: " + id));
    }
}
