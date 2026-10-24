package vn.edu.huflit.portal.studentcurriculum.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.entity.AcademicRecord;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/student/curriculum")
public class StudentCurriculumController {
 private final CurriculumRepository curriculumRepository;
 private final AcademicRecordRepository academicRecordRepository;
 private final CourseRepository courseRepository;

 @GetMapping
 public ResponseEntity<ApiResponse<Map<String,Object>>> getMyCurriculum(@AuthenticationPrincipal User user) {
   if(user.getCurriculumId()==null) throw new IllegalArgumentException("Tài khoản chưa được gán CTĐT");
   Curriculum c=curriculumRepository.findById(user.getCurriculumId())
       .orElseThrow(()->new IllegalArgumentException("Không tìm thấy CTĐT áp dụng"));
   Map<String,AcademicRecord> results=new HashMap<>();
   academicRecordRepository.findByStudentIdAndCurriculumId(user.getId(),c.getId())
       .forEach(r->results.put(r.getCourseCode(),r));
   Map<String,Course> courses=new HashMap<>();
   courseRepository.findAll().forEach(course->courses.put(course.getCourseCode(),course));
   List<Map<String,Object>> semesterViews=new ArrayList<>();
   int total=0, passed=0, totalCredits=0, passedCredits=0;
   Map<String, Map<String, Object>> groupProgress = new LinkedHashMap<>();
   for(Curriculum.SemesterItem semester : safe(c.getSemesters())) {
     List<Map<String,Object>> courseViews=new ArrayList<>();
     for(Curriculum.CurriculumCourseItem ci : safe(semester.getCourses())) {
       if(ci.getSpecializationCodes()!=null && !ci.getSpecializationCodes().isEmpty()
           && (user.getChosenSpecializationCode()==null || !ci.getSpecializationCodes().contains(user.getChosenSpecializationCode()))) continue;
       Course course=courses.get(ci.getCourseCode());
       AcademicRecord ar=results.get(ci.getCourseCode());
       boolean isPassed=ar!=null && "PASSED".equalsIgnoreCase(ar.getStatus());
       int credits=course!=null && course.getCredits()!=null?course.getCredits():0;
       List<String> prerequisites = safe(ci.getPrerequisiteCourseCodes());
       List<String> missingPrerequisites = prerequisites.stream()
           .filter(code -> !results.containsKey(code) || !"PASSED".equalsIgnoreCase(results.get(code).getStatus()))
           .toList();
       total++; totalCredits+=credits;
       if(isPassed){passed++; passedCredits+=credits;}
       String groupCode = ci.getKnowledgeGroupCode() == null || ci.getKnowledgeGroupCode().isBlank()
           ? "UNASSIGNED" : ci.getKnowledgeGroupCode();
       Map<String,Object> group = groupProgress.computeIfAbsent(groupCode, key -> {
         Map<String,Object> item = new LinkedHashMap<>();
         item.put("knowledgeGroupCode", key); item.put("totalCourses", 0); item.put("passedCourses", 0);
         item.put("totalCredits", 0); item.put("passedCredits", 0); return item;
       });
       group.put("totalCourses", (Integer)group.get("totalCourses") + 1);
       group.put("totalCredits", (Integer)group.get("totalCredits") + credits);
       if (isPassed) {
         group.put("passedCourses", (Integer)group.get("passedCourses") + 1);
         group.put("passedCredits", (Integer)group.get("passedCredits") + credits);
       }
       Map<String,Object> v=new LinkedHashMap<>();
       v.put("courseCode",ci.getCourseCode()); v.put("courseName",course==null?null:course.getCourseName());
       v.put("credits",credits); v.put("type",ci.getType()); v.put("knowledgeGroupCode",ci.getKnowledgeGroupCode());
       v.put("prerequisiteCourseCodes",prerequisites);
       v.put("prerequisitesSatisfied",missingPrerequisites.isEmpty());
       v.put("missingPrerequisiteCourseCodes",missingPrerequisites);
       v.put("highestScore10",ar==null?null:ar.getHighestScore10()); v.put("letterGrade",ar==null?null:ar.getHighestLetterGrade());
       v.put("status",isPassed?"PASSED":ar==null?"NOT_STUDIED":"FAILED");
       courseViews.add(v);
     }
     Map<String,Object> sv=new LinkedHashMap<>(); sv.put("semesterNumber",semester.getSemesterNumber()); sv.put("courses",courseViews); semesterViews.add(sv);
   }
   Map<String,Object> result=new LinkedHashMap<>();
   result.put("curriculumId",c.getId().toHexString()); result.put("curriculumCode",c.getCurriculumCode());
   result.put("curriculumName",c.getCurriculumName()); result.put("cohort",c.getCohort());
   result.put("majorId",c.getMajorId()==null?null:c.getMajorId().toHexString()); result.put("version",c.getVersion());
   result.put("totalCreditsInShownCourses",totalCredits); result.put("passedCredits",passedCredits);
   result.put("totalCourses",total); result.put("passedCourses",passed);
   result.put("completionPercent",total==0?0:Math.round(passed*10000.0/total)/100.0);
   result.put("knowledgeGroupProgress", new ArrayList<>(groupProgress.values()));
   result.put("semesters",semesterViews);
   return ResponseEntity.ok(ApiResponse.ok(result));
 }
 private <T> List<T> safe(List<T> values){return values==null?List.of():values;}
}
