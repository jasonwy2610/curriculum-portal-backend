package vn.edu.huflit.portal.studentcurriculum.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import vn.edu.huflit.portal.dto.response.ApiResponse;
import vn.edu.huflit.portal.entity.AcademicRecord;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/student/curriculum")
public class StudentCurriculumController {
 private final vn.edu.huflit.portal.curriculum.service.CurriculumEvaluationService curriculumEvaluationService;
 private final CurriculumRepository curriculumRepository;
 private final AcademicRecordRepository academicRecordRepository;
 private final CourseRepository courseRepository;
 private final ReplacementApplicability applicability;

 /** Evaluation without persisting new recognition evidence. */
 @GetMapping("/evaluation-preview")
 public ResponseEntity<ApiResponse<Map<String,Object>>> previewEvaluation(@AuthenticationPrincipal User user) {
   return ResponseEntity.ok(ApiResponse.ok(curriculumEvaluationService.evaluate(user)));
 }

 @GetMapping("/evaluation-official")
 public ResponseEntity<ApiResponse<Map<String,Object>>> officialEvaluation(@AuthenticationPrincipal User user) {
   return ResponseEntity.ok(ApiResponse.ok(curriculumEvaluationService.evaluateOfficial(user)));
 }

 @GetMapping
 public ResponseEntity<ApiResponse<Map<String,Object>>> getMyCurriculum(@AuthenticationPrincipal User user) {
   if (user.getCurriculumId()==null) throw new IllegalArgumentException("Tài khoản chưa được gán CTĐT");
   Curriculum c=curriculumRepository.findById(user.getCurriculumId())
       .orElseThrow(()->new IllegalArgumentException("Không tìm thấy CTĐT áp dụng"));
   Map<String,Object> official = curriculumEvaluationService.evaluateOfficial(user);
   Map<String,Boolean> passedByCode=new HashMap<>();
   Map<String,AcademicRecord> ownResults=new HashMap<>();
   // Kết quả môn thay thế có thể được ghi nhận dưới CTĐT mới: tìm theo sinh viên, không giới hạn curriculumId.
   for (AcademicRecord r:academicRecordRepository.findByStudentId(user.getId())) {
     if (r.getCourseCode()==null) continue;
     if ("PASSED".equalsIgnoreCase(r.getStatus())) passedByCode.put(r.getCourseCode(),true);
     if (c.getId().equals(r.getCurriculumId())) ownResults.put(r.getCourseCode(),r);
   }
   Map<String,Course> catalog=new HashMap<>();
   courseRepository.findAll().forEach(course->catalog.put(course.getCourseCode(),course));

   // Next-curriculum metadata for display only.
   Integer nextYear=c.getAppliedYear()==null?null:c.getAppliedYear()+2;
   Curriculum next=null;
   if (nextYear!=null && c.getMajorId()!=null) {
     next=curriculumRepository.findAll().stream()
         .filter(x->c.getMajorId().equals(x.getMajorId()) && nextYear.equals(x.getAppliedYear()))
         .filter(x->!"DRAFT".equalsIgnoreCase(x.getStatus()) && !"INACTIVE".equalsIgnoreCase(x.getStatus()))
         .max(Comparator.comparing(x->x.getVersion()==null?0:x.getVersion())).orElse(null);
   }
   final Curriculum following=next;
   @SuppressWarnings("unchecked")
   Map<String,List<String>> recognizedMap =
       (Map<String,List<String>>) official.getOrDefault("recognizedReplacements", Map.of());
   @SuppressWarnings("unchecked")
   List<String> recognizedRuleIds =
       (List<String>) official.getOrDefault("recognizedReplacementRuleIds", List.of());
   Instant now = Instant.now();
   Map<String,List<ReplacementRule>> rulesByOld = new HashMap<>();
   for (var window : applicability.windows(user, c, now)) {
     ReplacementRule rule = window.rule();
     if (!window.contains(now) && !recognizedRuleIds.contains(rule.getId().toHexString())) continue;
     for (String old : safe(rule.getOldCourseCodes())) {
       rulesByOld.computeIfAbsent(old, ignored -> new ArrayList<>()).add(rule);
     }
   }

   int total=0,passed=0,totalCredits=0,passedCredits=0;
   int mandatoryCredits=0, mandatoryPassedCredits=0, electiveCredits=0, electivePassedCredits=0;
   List<Map<String,Object>> semesters=new ArrayList<>();
   Map<String,Map<String,Object>> groups=new LinkedHashMap<>();
   for (Curriculum.SemesterItem semester:safe(c.getSemesters())) {
     List<Map<String,Object>> courseViews=new ArrayList<>();
     int semesterCredits=0,semesterPassedCredits=0,semesterPassed=0;
     for (Curriculum.CurriculumCourseItem ci:safe(semester.getCourses())) {
       if (ci.getSpecializationCodes()!=null && !ci.getSpecializationCodes().isEmpty()
           && (user.getChosenSpecializationCode()==null || !ci.getSpecializationCodes().contains(user.getChosenSpecializationCode()))) continue;
       String code=ci.getCourseCode();
       Course course=catalog.get(code);
       AcademicRecord ar=ownResults.get(code);
       int credits=ci.getCredits()!=null?ci.getCredits():course!=null&&course.getCredits()!=null?course.getCredits():0;
       boolean originalPassed=Boolean.TRUE.equals(passedByCode.get(code));
       List<Map<String,Object>> replacementViews=new ArrayList<>();
       boolean replacementPassed=recognizedMap.containsKey(code);
       for (ReplacementRule rule:rulesByOld.getOrDefault(code,List.of())) {
         int effectiveYear=rule.getEffectiveDate().toInstant().atZone(ZoneId.systemDefault()).getYear();
         boolean allPassed=safe(rule.getNewCourseCodes()).stream().allMatch(newCode->Boolean.TRUE.equals(passedByCode.get(newCode)));
         boolean supported=rule.getRuleType()!=null && Set.of("1-1", "1-N", "N-1").contains(rule.getRuleType());
         boolean recognized=replacementPassed && recognizedRuleIds.contains(rule.getId().toHexString())
             && new HashSet<>(safe(rule.getNewCourseCodes())).equals(new HashSet<>(recognizedMap.get(code)));
         Map<String,Object> rv=new LinkedHashMap<>();
         rv.put("ruleType",rule.getRuleType()); rv.put("oldCourseCodes",safe(rule.getOldCourseCodes()));
         rv.put("newCourseCodes",safe(rule.getNewCourseCodes()));
         rv.put("newCourses",safe(rule.getNewCourseCodes()).stream().map(newCode->{
           Map<String,Object> item=new LinkedHashMap<>(); item.put("courseCode",newCode);
           Course nc=catalog.get(newCode); item.put("courseName",nc==null?newCode:nc.getCourseName());
           item.put("credits",nc==null?null:nc.getCredits()); item.put("passed",Boolean.TRUE.equals(passedByCode.get(newCode)));
           return item;
         }).collect(Collectors.toList()));
         rv.put("effectiveYear",effectiveYear); rv.put("effectiveDate",rule.getEffectiveDate());
         rv.put("reason",rule.getReason()); rv.put("note",rule.getNote());
         rv.put("applicableCohorts",safe(rule.getApplicableCohorts()));
         rv.put("recognized",recognized); rv.put("allReplacementCoursesPassed",allPassed);
         rv.put("automaticRecognitionSupported",supported);
         rv.put("nextCurriculumCode",following==null?null:following.getCurriculumCode());
         rv.put("nextCurriculumName",following==null?null:following.getCurriculumName());
         replacementViews.add(rv);
       }
       boolean isPassed=originalPassed || replacementPassed;
       List<String> prerequisites=safe(ci.getPrerequisiteCourseCodes());
       List<String> missing=prerequisites.stream().filter(pre->!Boolean.TRUE.equals(passedByCode.get(pre))).collect(Collectors.toList());
       total++; totalCredits+=credits; semesterCredits+=credits;
       boolean elective="ELECTIVE".equalsIgnoreCase(ci.getType());
       if (elective) {electiveCredits+=credits; if(isPassed) electivePassedCredits+=credits;}
       else {mandatoryCredits+=credits; if(isPassed) mandatoryPassedCredits+=credits;}
       if (isPassed) {passed++;passedCredits+=credits;semesterPassed++;semesterPassedCredits+=credits;}
       String groupCode=ci.getKnowledgeGroupCode()==null||ci.getKnowledgeGroupCode().isBlank()?"UNASSIGNED":ci.getKnowledgeGroupCode();
       Map<String,Object> group=groups.computeIfAbsent(groupCode,key->{
         Map<String,Object> v=new LinkedHashMap<>();v.put("knowledgeGroupCode",key);
         v.put("totalCourses",0);v.put("passedCourses",0);v.put("totalCredits",0);v.put("passedCredits",0);v.put("mandatoryCredits",0);v.put("mandatoryPassedCredits",0);v.put("electiveCredits",0);v.put("electivePassedCredits",0);return v;
       });
       group.put("totalCourses",(Integer)group.get("totalCourses")+1);
       group.put("totalCredits",(Integer)group.get("totalCredits")+credits);
       String totalKey=elective?"electiveCredits":"mandatoryCredits";
       String passedKey=elective?"electivePassedCredits":"mandatoryPassedCredits";
       group.put(totalKey,(Integer)group.get(totalKey)+credits);
       if(isPassed)group.put(passedKey,(Integer)group.get(passedKey)+credits);
       if (isPassed) {group.put("passedCourses",(Integer)group.get("passedCourses")+1);group.put("passedCredits",(Integer)group.get("passedCredits")+credits);}
       Map<String,Object> v=new LinkedHashMap<>();
       v.put("courseCode",code);v.put("courseName",ci.getCourseName()!=null?ci.getCourseName():course==null?null:course.getCourseName());
       v.put("credits",credits);v.put("type",ci.getType());v.put("knowledgeGroupCode",ci.getKnowledgeGroupCode());
       v.put("prerequisiteCourseCodes",prerequisites);v.put("prerequisitesSatisfied",missing.isEmpty());
       v.put("missingPrerequisiteCourseCodes",missing);v.put("highestScore10",ar==null?null:ar.getHighestScore10());
       v.put("letterGrade",ar==null?null:ar.getHighestLetterGrade());
       v.put("status",isPassed?"PASSED":ar==null?"NOT_STUDIED":"FAILED");
       v.put("completedByReplacement",!originalPassed&&replacementPassed);
       v.put("replacementRules",replacementViews);courseViews.add(v);
     }
     Map<String,Object> sv=new LinkedHashMap<>();sv.put("semesterNumber",semester.getSemesterNumber());
     sv.put("courses",courseViews);sv.put("totalCourses",courseViews.size());sv.put("passedCourses",semesterPassed);
     sv.put("totalCredits",semesterCredits);sv.put("passedCredits",semesterPassedCredits);semesters.add(sv);
   }
   Map<String,Object> result=new LinkedHashMap<>();
   result.put("curriculumId",c.getId().toHexString());result.put("curriculumCode",c.getCurriculumCode());
   result.put("curriculumName",c.getCurriculumName());result.put("cohort",c.getCohort());
   result.put("majorId",c.getMajorId()==null?null:c.getMajorId().toHexString());result.put("version",c.getVersion());
   result.put("appliedYear",c.getAppliedYear());result.put("requiredCredits",official.get("requiredCredits"));
   result.put("nextCurriculumCode",following==null?null:following.getCurriculumCode());
   int requiredCredits=c.resolvedTotalCredits()==null?0:c.resolvedTotalCredits();
   Integer configuredMandatory=c.resolvedMandatoryCredits();
   boolean creditPlanValid=requiredCredits>0 && configuredMandatory!=null && configuredMandatory>=0 && configuredMandatory<=requiredCredits;
   Integer electiveQuota=creditPlanValid?requiredCredits-configuredMandatory:null;
   // Khong tinh tien do neu chi tieu bat buoc chua duoc cau hinh.
   int recognizedCredits=((Number)official.get("recognizedCredits")).intValue();
   requiredCredits=((Number)official.get("requiredCredits")).intValue();
   creditPlanValid=true;
   result.put("mandatoryCredits",configuredMandatory);
   result.put("evaluation",official);
   result.put("groupProgress",official.get("groupProgress"));
   result.put("officialGraduationEligible",official.get("officialGraduationEligible"));
   result.put("cumulativeGpa4",official.get("cumulativeGpa4"));
   result.put("semesterGpa4",official.get("semesterGpa4"));
   result.put("mandatoryCatalogCredits",mandatoryCredits);
   result.put("mandatoryPassedCredits",mandatoryPassedCredits);
   result.put("electiveCatalogCredits",electiveCredits);
   result.put("electivePassedCredits",electivePassedCredits);
   result.put("electiveQuotaEstimate",electiveQuota);
   result.put("recognizedCredits",creditPlanValid?recognizedCredits:null);
   result.put("creditPlanValid",creditPlanValid);
   result.put("totalCreditsInShownCourses",totalCredits);result.put("passedCredits",passedCredits);
   result.put("totalCourses",total);result.put("passedCourses",passed);
   result.put("completionPercent",requiredCredits==0?0:Math.round(recognizedCredits*10000.0/requiredCredits)/100.0);
   result.put("creditCompletionPercent",creditPlanValid?Math.round(Math.min(recognizedCredits,requiredCredits)*10000.0/requiredCredits)/100.0:null);
   result.put("knowledgeGroupProgress",new ArrayList<>(groups.values()));result.put("semesters",semesters);
   return ResponseEntity.ok(ApiResponse.ok(result));
 }
 private <T> List<T> safe(List<T> values){return values==null?List.of():values;}
}
