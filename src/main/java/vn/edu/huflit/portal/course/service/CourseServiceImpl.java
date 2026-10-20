package vn.edu.huflit.portal.course.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.changelog.model.ChangeLog;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.course.dto.CourseResponse;
import vn.edu.huflit.portal.course.dto.CreateCourseRequest;
import vn.edu.huflit.portal.course.dto.UpdateCourseRequest;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.entity.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private final CourseRepository courseRepository;
    private final MongoTemplate mongoTemplate;
    private final ChangeLogService changeLogService;

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

    @Override
    public CourseResponse getCourseById(String id) {
        Course course = findCourseEntityById(id);
        return CourseResponse.fromEntity(course);
    }

    @Override
    public CourseResponse getCourseByCourseCode(String courseCode) {
        if (courseCode == null || courseCode.trim().isEmpty()) {
            throw new RuntimeException("Mã học phần không được để trống");
        }
        Course course = courseRepository.findByCourseCode(courseCode.trim())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy học phần với mã: " + courseCode));
        return CourseResponse.fromEntity(course);
    }

    @Override
    public CourseResponse createCourse(CreateCourseRequest request) {
        String courseCode = request.getCourseCode().trim();

        if (courseRepository.existsByCourseCode(courseCode)) {
            throw new RuntimeException("Mã học phần '" + courseCode + "' đã tồn tại trong hệ thống");
        }

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

        // F42: Ghi nhận vết kiểm toán khi tạo mới học phần (action = CREATE)
        List<ChangeLog.FieldChangeItem> changes = new ArrayList<>();
        changes.add(new ChangeLog.FieldChangeItem("courseCode", null, saved.getCourseCode()));
        changes.add(new ChangeLog.FieldChangeItem("courseName", null, saved.getCourseName()));
        changes.add(new ChangeLog.FieldChangeItem("credits", null, saved.getCredits()));
        if (saved.getDepartment() != null) {
            changes.add(new ChangeLog.FieldChangeItem("department", null, saved.getDepartment()));
        }
        changeLogService.recordChange("Course", saved.getId(), "CREATE", changes, getCurrentUserId());

        return CourseResponse.fromEntity(saved);
    }

    @Override
    public CourseResponse updateCourse(String id, UpdateCourseRequest request) {
        Course course = findCourseEntityById(id);

        // F42: So khớp và ghi vết thay đổi chi tiết từng trường (field diffs)
        List<ChangeLog.FieldChangeItem> changes = new ArrayList<>();
        String newCourseName = request.getCourseName().trim();
        if (!Objects.equals(course.getCourseName(), newCourseName)) {
            changes.add(new ChangeLog.FieldChangeItem("courseName", course.getCourseName(), newCourseName));
            course.setCourseName(newCourseName);
        }

        if (!Objects.equals(course.getCredits(), request.getCredits())) {
            changes.add(new ChangeLog.FieldChangeItem("credits", course.getCredits(), request.getCredits()));
            course.setCredits(request.getCredits());
        }

        if (!Objects.equals(course.getTheoryHours(), request.getTheoryHours())) {
            changes.add(new ChangeLog.FieldChangeItem("theoryHours", course.getTheoryHours(), request.getTheoryHours()));
            course.setTheoryHours(request.getTheoryHours());
        }

        if (!Objects.equals(course.getPracticeHours(), request.getPracticeHours())) {
            changes.add(new ChangeLog.FieldChangeItem("practiceHours", course.getPracticeHours(), request.getPracticeHours()));
            course.setPracticeHours(request.getPracticeHours());
        }

        if (request.getDescription() != null && !Objects.equals(course.getDescription(), request.getDescription())) {
            changes.add(new ChangeLog.FieldChangeItem("description", course.getDescription(), request.getDescription()));
            course.setDescription(request.getDescription());
        }

        if (request.getDepartment() != null && !Objects.equals(course.getDepartment(), request.getDepartment())) {
            changes.add(new ChangeLog.FieldChangeItem("department", course.getDepartment(), request.getDepartment()));
            course.setDepartment(request.getDepartment());
        }

        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            String newStatus = request.getStatus().trim().toUpperCase();
            if (!Objects.equals(course.getStatus(), newStatus)) {
                changes.add(new ChangeLog.FieldChangeItem("status", course.getStatus(), newStatus));
                course.setStatus(newStatus);
            }
        }

        Course updated = courseRepository.save(course);

        if (!changes.isEmpty()) {
            changeLogService.recordChange("Course", updated.getId(), "UPDATE", changes, getCurrentUserId());
        }

        return CourseResponse.fromEntity(updated);
    }

    @Override
    public CourseResponse deleteCourse(String id) {
        Course course = findCourseEntityById(id);

        String oldStatus = course.getStatus();
        course.setStatus("INACTIVE");
        Course updated = courseRepository.save(course);

        // F42: Ghi nhận vết kiểm toán khi xóa mềm học phần (action = UPDATE)
        List<ChangeLog.FieldChangeItem> changes = List.of(
                new ChangeLog.FieldChangeItem("status", oldStatus, "INACTIVE")
        );
        changeLogService.recordChange("Course", updated.getId(), "UPDATE", changes, getCurrentUserId());

        return CourseResponse.fromEntity(updated);
    }

    private Course findCourseEntityById(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new RuntimeException("ID học phần không hợp lệ: " + id);
        }
        return courseRepository.findById(new ObjectId(id))
                .orElseThrow(() -> new RuntimeException("Không tìm thấy học phần với ID: " + id));
    }

    private ObjectId getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                return user.getId();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
