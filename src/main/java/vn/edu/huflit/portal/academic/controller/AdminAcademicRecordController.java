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

@RestController
@RequestMapping("/api/v1/admin/academic-records")
@RequiredArgsConstructor
public class AdminAcademicRecordController {
 private final AcademicRecordService service;
 private final UserRepository users;

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
   if(student.getCurriculumId()!=null && !student.getCurriculumId().equals(curriculumId))
      throw new IllegalArgumentException("CTĐT không khớp CTĐT đang áp dụng cho sinh viên");
   return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật kết quả học tập",service.addAttempt(studentId,req.getCourseCode(),curriculumId,req.getAttempt())));
 }
 private ObjectId parse(String value,String field) {
   if(!ObjectId.isValid(value)) throw new IllegalArgumentException(field+" không hợp lệ");
   return new ObjectId(value);
 }
}
