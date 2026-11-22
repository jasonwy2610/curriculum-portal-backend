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
import vn.edu.huflit.portal.curriculum.service.CurriculumTracks;
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
   return curriculumView(user,true);
 }

 /** Same read projection, without recording new recognition evidence. */
 @GetMapping("/preview")
 public ResponseEntity<ApiResponse<Map<String,Object>>> previewCurriculum(@AuthenticationPrincipal User user) {
   return curriculumView(user,false);
 }

 private ResponseEntity<ApiResponse<Map<String,Object>>> curriculumView(User user,boolean officialRead) {
   if (user.getCurriculumId()==null) throw new IllegalArgumentException("Tài khoản chưa được gán CTĐT");
   Curriculum storedCurriculum=curriculumRepository.findById(user.getCurriculumId())
       .orElseThrow(()->new IllegalArgumentException("Không tìm thấy CTĐT áp dụng"));
   Curriculum c = CurriculumTracks.forStudent(storedCurriculum, user);
   final Curriculum selectedCurriculum=c;
   Map<String,Object> official = officialRead?curriculumEvaluationService.evaluateOfficial(user):curriculumEvaluationService.evaluate(user);
   @SuppressWarnings("unchecked")
   List<Map<String,Object>> progress=(List<Map<String,Object>>)official.get("groupProgress");
   Set<String> electiveCodes=c.getRequirementGroups().stream()
       .filter(g->g.getKind()!=null && g.getKind().startsWith("ELECTIVE"))
       .flatMap(g->safe(g.getEligibleCourseCodes()).stream()).collect(Collectors.toSet());
   List<Curriculum.RequirementGroupItem> graduationGroups=c.getRequirementGroups().stream()
       .filter(g->"GRADUATION".equals(g.getKind()) && !Boolean.FALSE.equals(g.getCountsTowardGraduation())).toList();
   Set<String> graduationCodes=graduationGroups.stream().flatMap(g->safe(g.getCompletionOptions()).stream())
       .flatMap(o->safe(o.getCourseCodes()).stream()).collect(Collectors.toSet());
   Set<String> independentMandatory=c.getRequirementGroups().stream()
       .filter(g->g.getKind()!=null && g.getKind().startsWith("MANDATORY") && !Boolean.FALSE.equals(g.getCountsTowardGraduation()))
       .flatMap(g->safe(g.getEligibleCourseCodes()).stream()).collect(Collectors.toSet());
   Set<String> completeGraduationGroups=progress.stream().filter(g->"GRADUATION".equals(g.get("kind")) && Boolean.TRUE.equals(g.get("complete")))
       .map(g->String.valueOf(g.get("groupCode"))).collect(Collectors.toSet());
   boolean electiveGroupComplete=progress.stream()
       .filter(g->String.valueOf(g.get("kind")).startsWith("ELECTIVE"))
       .anyMatch(g->Boolean.TRUE.equals(g.get("complete")));
   Map<String,Boolean> passedByCode=new HashMap<>();
   Map<String,AcademicRecord> ownResults=new HashMap<>();
   Map<String,List<AcademicRecord>> learningResults=new HashMap<>();
   // Kết quả môn thay thế có thể được ghi nhận dưới CTĐT mới: tìm theo sinh viên, không giới hạn curriculumId.
   for (AcademicRecord r:academicRecordRepository.findByStudentId(user.getId())) {
     if (r.getCourseCode()==null) continue;
     learningResults.computeIfAbsent(r.getCourseCode(),key->new ArrayList<>()).add(r);
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
         .filter(x->selectedCurriculum.getMajorId().equals(x.getMajorId()) && nextYear.equals(x.getAppliedYear()))
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
   @SuppressWarnings("unchecked")
   List<Map<String,Object>> historicalEvidence =
       (List<Map<String,Object>>) official.getOrDefault("historicalRecognitionEvidence", List.of());
   Instant now = Instant.now();
   Map<String,List<ReplacementRule>> rulesByOld = new HashMap<>();
   Set<String> currentRuleIds = new HashSet<>();
   Map<String,ReplacementApplicability.Window> ruleWindows=new HashMap<>();
   for (var window : applicability.windows(user, c, now)) {
     ReplacementRule rule = window.rule();
     ruleWindows.put(rule.getId().toHexString(),window);
     if (window.verifies(now)) currentRuleIds.add(rule.getId().toHexString());
     if (!window.verifies(now) && !recognizedRuleIds.contains(rule.getId().toHexString())) continue;
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
         boolean supported=currentRuleIds.contains(rule.getId().toHexString());
         boolean recognized=replacementPassed && recognizedRuleIds.contains(rule.getId().toHexString())
             && new HashSet<>(safe(rule.getNewCourseCodes())).equals(new HashSet<>(recognizedMap.get(code)));
         Map<String,Object> rv=new LinkedHashMap<>();
         rv.put("ruleId",rule.getId().toHexString()); rv.put("versionNumber",rule.getVersionNumber());
         rv.put("ruleType",rule.getRuleType()); rv.put("oldCourseCodes",safe(rule.getOldCourseCodes()));
         rv.put("newCourseCodes",safe(rule.getNewCourseCodes()));
         rv.put("effectiveYear",effectiveYear); rv.put("effectiveDate",rule.getEffectiveDate());
         rv.put("reason",rule.getReason()); rv.put("note",rule.getNote());
         rv.put("applicableCohorts",safe(rule.getApplicableCohorts()));
         rv.put("recognized",recognized); rv.put("allReplacementCoursesPassed",allPassed);
         rv.put("automaticRecognitionSupported",supported);
         if (recognized) {
           historicalEvidence.stream()
               .filter(snapshot -> rule.getId().toHexString().equals(snapshot.get("ruleId")))
               .findFirst().ifPresent(snapshot -> {
                 rv.putAll(snapshot);
                 rv.put("historicalEvidence",true);
               });
         }
         rv.put("nextCurriculumCode",following==null?null:following.getCurriculumCode());
         rv.put("nextCurriculumName",following==null?null:following.getCurriculumName());
         replacementViews.add(rv);
       }
       // A valid entitlement remains visible even if its rule is no longer
       // available in the current tree/timeline. Never fabricate historical dates.
       if (replacementPassed && replacementViews.stream().noneMatch(v->Boolean.TRUE.equals(v.get("recognized")))) {
         for (Map<String,Object> snapshot:historicalEvidence) {
           @SuppressWarnings("unchecked") List<String> olds=(List<String>)snapshot.get("oldCourseCodes");
           @SuppressWarnings("unchecked") List<String> news=(List<String>)snapshot.get("newCourseCodes");
           if (!olds.contains(code) || !new HashSet<>(news).equals(new HashSet<>(recognizedMap.get(code)))) continue;
           Map<String,Object> rv=new LinkedHashMap<>(snapshot);
           rv.put("ruleType",olds.size()>1?"N-1":news.size()>1?"1-N":"1-1");
           rv.put("recognized",true); rv.put("historicalEvidence",true);
           rv.put("automaticRecognitionSupported",false);
           rv.put("note","Đã công nhận theo bằng chứng lịch sử; không dùng để cấp công nhận mới.");
           replacementViews.add(rv);
         }
       }
       for(Map<String,Object> view:replacementViews) {
         @SuppressWarnings("unchecked") List<String> oldCodes=(List<String>)view.get("oldCourseCodes");
         @SuppressWarnings("unchecked") List<String> newCodes=(List<String>)view.get("newCourseCodes");
         view.put("oldCourses",replacementCourses(oldCodes,catalog,learningResults));
         view.put("newCourses",replacementCourses(newCodes,catalog,learningResults));
         var window=ruleWindows.get(String.valueOf(view.get("ruleId")));
         if(window!=null) {view.put("startsAt",Date.from(window.start()));view.put("endsAt",window.end()==null?null:Date.from(window.end()));}
         view.put("curriculumId",c.getId().toHexString());view.put("curriculumVersion",c.getVersion());
         view.put("specializationCode",user.getChosenSpecializationCode());view.put("studentCohort",user.getCohort());
         boolean entitled=Boolean.TRUE.equals(view.get("recognized"));
         view.put("recognitionState",Boolean.TRUE.equals(view.get("historicalEvidence"))?"HISTORICAL_RECOGNIZED":
             entitled?(officialRead?"RECOGNIZED":"ELIGIBLE"):"NOT_RECOGNIZED");
       }
       boolean isPassed=originalPassed || replacementPassed;
       List<String> prerequisites=safe(ci.getPrerequisiteCourseCodes());
       List<String> missing=prerequisites.stream().filter(pre->!Boolean.TRUE.equals(passedByCode.get(pre))).collect(Collectors.toList());
       total++; totalCredits+=credits; semesterCredits+=credits;
       boolean elective="ELECTIVE".equalsIgnoreCase(ci.getType());
       if (elective) {electiveCredits+=credits; if(isPassed) electivePassedCredits+=credits;}
       else if(independentMandatory.contains(code)) {mandatoryCredits+=credits; if(isPassed) mandatoryPassedCredits+=credits;}
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
       if(elective || independentMandatory.contains(code)) {
         group.put(totalKey,(Integer)group.get(totalKey)+credits);
         if(isPassed)group.put(passedKey,(Integer)group.get(passedKey)+credits);
       }
       if (isPassed) {group.put("passedCourses",(Integer)group.get("passedCourses")+1);group.put("passedCredits",(Integer)group.get("passedCredits")+credits);}
       Map<String,Object> v=new LinkedHashMap<>();
       v.put("courseCode",code);v.put("courseName",ci.getCourseName()!=null?ci.getCourseName():course==null?null:course.getCourseName());
       v.put("credits",credits);v.put("type",ci.getType());v.put("knowledgeGroupCode",ci.getKnowledgeGroupCode());
       v.put("prerequisiteCourseCodes",prerequisites);v.put("prerequisitesSatisfied",missing.isEmpty());
       v.put("missingPrerequisiteCourseCodes",missing);v.put("highestScore10",ar==null?null:ar.getHighestScore10());
       v.put("letterGrade",ar==null?null:ar.getHighestLetterGrade());
       v.put("status",isPassed?"PASSED":ar==null?"NOT_STUDIED":"FAILED");
       // Display annotations only; group completion and credit allocation remain official.
       boolean graduationAlternative=graduationCodes.contains(code) && !independentMandatory.contains(code);
       v.put("requiredForCompletion",independentMandatory.contains(code));
       v.put("graduationAlternative",graduationAlternative);
       v.put("graduationGroupComplete",graduationAlternative && graduationGroups.stream()
           .filter(g->safe(g.getCompletionOptions()).stream().anyMatch(o->safe(o.getCourseCodes()).contains(code)))
           .allMatch(g->completeGraduationGroups.contains(g.getGroupCode())));
       v.put("electiveGroupComplete",electiveCodes.contains(code) && electiveGroupComplete);
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
   result.put("specializationCode",user.getChosenSpecializationCode());
   result.put("majorId",c.getMajorId()==null?null:c.getMajorId().toHexString());result.put("version",c.getVersion());
   result.put("appliedYear",c.getAppliedYear());result.put("requiredCredits",official.get("requiredCredits"));
   result.put("nextCurriculumCode",following==null?null:following.getCurriculumCode());
   int requiredCredits=((Number)official.get("requiredCredits")).intValue();
   Integer configuredMandatory=c.getRequirementGroups().stream()
       .filter(group -> group.getKind()!=null && group.getKind().startsWith("MANDATORY")
           && !Boolean.FALSE.equals(group.getCountsTowardGraduation()))
       .mapToInt(group -> group.getRequiredCredits()==null?0:group.getRequiredCredits()).sum();
   boolean creditPlanValid=requiredCredits>0 && configuredMandatory!=null && configuredMandatory>=0 && configuredMandatory<=requiredCredits;
   Integer electiveQuota=c.getRequirementGroups().stream()
       .filter(g->g.getKind()!=null && g.getKind().startsWith("ELECTIVE")
           && !Boolean.FALSE.equals(g.getCountsTowardGraduation()))
       .mapToInt(g->g.getRequiredCredits()==null?0:g.getRequiredCredits()).sum();
   // Khong tinh tien do neu chi tieu bat buoc chua duoc cau hinh.
   int recognizedCredits=((Number)official.get("recognizedCredits")).intValue();
   requiredCredits=((Number)official.get("requiredCredits")).intValue();
   creditPlanValid=true;
   result.put("mandatoryCredits",configuredMandatory);
   result.put("evaluation",official);
   result.put("groupProgress",official.get("groupProgress"));
   // Read-only configuration from the authenticated student's selected tree only.
   result.put("graduationGroups",safe(c.getRequirementGroups()).stream()
       .filter(g -> "GRADUATION".equals(g.getKind())).toList());
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
 /** Catalog names and learning activity are display data, independent of recognition/GPA. */
 private List<Map<String,Object>> replacementCourses(List<String> codes,Map<String,Course> catalog,Map<String,List<AcademicRecord>> results) {
   return safe(codes).stream().map(code->{
     Map<String,Object> item=new LinkedHashMap<>(); Course course=catalog.get(code);
     var records=results.getOrDefault(code,List.of());
     int attempts=records.stream().mapToInt(r->safe(r.getAttempts()).size()).sum();
     boolean passed=records.stream().anyMatch(r->"PASSED".equals(r.getStatus()));
     String state=passed?"PASSED":records.stream().anyMatch(r->"FAILED".equals(r.getStatus()))?"FAILED":
       attempts>0||records.stream().anyMatch(r->!"NOT_STUDIED".equals(r.getStatus()))?"UNVERIFIED":"NOT_STUDIED";
     item.put("courseCode",code);item.put("courseName",course==null?null:course.getCourseName());
     item.put("credits",course==null?null:course.getCredits());item.put("passed",passed);
     item.put("learningStatus",state);item.put("attemptCount",attempts);return item;
   }).toList();
 }
 private <T> List<T> safe(List<T> values){return values==null?List.of():values;}
}
