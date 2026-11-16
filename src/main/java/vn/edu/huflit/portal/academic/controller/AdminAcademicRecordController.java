package vn.edu.huflit.portal.academic.controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.request.AdminAddAcademicAttemptRequest;
import vn.edu.huflit.portal.dto.response.*;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.repository.UserRepository;
import vn.edu.huflit.portal.academic.service.AcademicRecordService;
import java.util.List;
import java.util.Date;
import java.time.LocalDate;
import java.time.Instant;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;

@RestController
@RequestMapping("/api/v1/admin/academic-records")
@RequiredArgsConstructor
public class AdminAcademicRecordController {
 private final AcademicRecordService service;
 private final UserRepository users;
 private final CurriculumRepository curriculums;
 private final CourseRepository courses;
 private final ReplacementApplicability applicability;

 @GetMapping("/student/{studentId}")
 public ResponseEntity<ApiResponse<List<AcademicRecordResponse>>> byStudent(@PathVariable String studentId) {
   ObjectId id=parse(studentId,"studentId");
   if(!users.existsById(id)) throw new IllegalArgumentException("Không tìm thấy sinh viên");
   return ResponseEntity.ok(ApiResponse.ok(service.getMyAcademicRecords(id)));
 }
 @PostMapping("/attempts")
 public ResponseEntity<ApiResponse<AcademicRecordResponse>> add(@Valid @RequestBody AdminAddAcademicAttemptRequest req) {
   ObjectId studentId=parse(req.getStudentId(),"studentId");
   ObjectId curriculumId=parse(req.getCurriculumId(),"curriculumId");
   User student=users.findById(studentId).orElseThrow(()->new IllegalArgumentException("Không tìm thấy sinh viên"));
   if(!"STUDENT".equals(student.getRole())) throw new IllegalArgumentException("Tài khoản được chọn không phải sinh viên");
   if(req.getAttempt()==null) throw new IllegalArgumentException("Thiếu dữ liệu lần học");
   if(req.getAttempt().getScore10()==null || req.getAttempt().getScore10()<0 || req.getAttempt().getScore10()>10)throw new IllegalArgumentException("Điểm phải trong khoảng 0–10");
   if(!req.getCourseCode().matches("[A-Za-z0-9_-]{2,30}"))throw new IllegalArgumentException("Mã học phần không hợp lệ");
   Curriculum curriculum=curriculums.findById(curriculumId).orElseThrow(()->new IllegalArgumentException("CTĐT không tồn tại"));
   if(!courses.existsByCourseCode(req.getCourseCode()))throw new IllegalArgumentException("Học phần không có trong danh mục");
   boolean inCurriculum=curriculum.getSemesters()!=null && curriculum.getSemesters().stream().anyMatch(sem->sem.getCourses()!=null&&sem.getCourses().stream().anyMatch(c->req.getCourseCode().equals(c.getCourseCode())));
   String semesterCode=req.getAttempt().getSemesterCode();
   if(semesterCode==null || !semesterCode.matches("20[0-9]{2}-[123]"))
       throw new IllegalArgumentException("Học kỳ thực tế phải có dạng 2027-1, 2027-2 hoặc 2027-3");
   int year=Integer.parseInt(semesterCode.substring(0,4));
   if(student.getCohort()!=null && student.getCohort().matches("K20[0-9]{2}") && year<Integer.parseInt(student.getCohort().substring(1)))
       throw new IllegalArgumentException("Không thể nhập điểm trước khóa tuyển sinh");
   if(year>LocalDate.now().getYear()+1)throw new IllegalArgumentException("Năm học thực tế nằm quá xa trong tương lai");
   if (student.getCurriculumId() != null && !student.getCurriculumId().equals(curriculumId))
      throw new IllegalArgumentException("CTĐT không khớp CTĐT đang áp dụng cho sinh viên");

   Instant now = Instant.now();
   Date completedAt = req.getAttempt().getCompletedAt();
   if (completedAt != null && completedAt.toInstant().isAfter(now))
       throw new IllegalArgumentException("Ngày hoàn thành không được trong tương lai");
   if (!inCurriculum && completedAt == null)
       throw new IllegalArgumentException("Cần nhập ngày hoàn thành thực tế (completedAt) cho học phần thay thế ngoài CTĐT");
   if (!inCurriculum && applicability.windows(student, curriculum, now).stream().noneMatch(window ->
       window.verifies(completedAt.toInstant()) && window.rule().getNewCourseCodes() != null
           && window.rule().getNewCourseCodes().contains(req.getCourseCode())))
       throw new IllegalArgumentException("Học phần không có quy tắc thay thế hợp lệ tại ngày hoàn thành");

   return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật kết quả học tập",service.addAttempt(studentId,req.getCourseCode(),curriculumId,req.getAttempt())));
 }
 @PatchMapping("/{recordId}/attempts/{attemptId}")
 public ResponseEntity<ApiResponse<AcademicRecordResponse>> correct(
         @PathVariable String recordId, @PathVariable String attemptId,
         @Valid @RequestBody CorrectAttemptRequest req) {
   return ResponseEntity.ok(ApiResponse.ok("Đã sửa điểm và ghi nhật ký",
       service.correctAttempt(parse(recordId, "recordId"), attemptId, req.getScore10(), req.getReason())));
 }

 @lombok.Data
 public static class CorrectAttemptRequest {
   @jakarta.validation.constraints.NotNull
   @jakarta.validation.constraints.DecimalMin("0.0")
   @jakarta.validation.constraints.DecimalMax("10.0")
   private Double score10;
   @jakarta.validation.constraints.NotBlank
   private String reason;
 }
 private ObjectId parse(String value,String field) {
   if(!ObjectId.isValid(value)) throw new IllegalArgumentException(field+" không hợp lệ");
   return new ObjectId(value);
 }
}
