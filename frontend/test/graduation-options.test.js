import test from 'node:test';
import assert from 'node:assert/strict';
import {treeValidationMessage, treeWarnings} from '../src/curriculum-ui.js';
import {curriculumCourseStatus, curriculumCourseNeedsAttention} from '../src/curriculum-tracks.js';
import {graduationLabel, graduationGuide, graduationSummary, suggestedGraduationCourses, graduationCatalog, graduationConflict} from '../src/graduation-options.js';
export const catalog=[{courseCode:'T',courseName:'Khóa luận tốt nghiệp',credits:6},
  {courseCode:'P1',courseName:'Chuyên đề tốt nghiệp 1',credits:3},{courseCode:'P2',courseName:'Đồ án tốt nghiệp',credits:3},
  {courseCode:'M',courseName:'Toán',credits:3},{courseCode:'E',courseName:'Tự chọn',credits:3},
  {courseCode:'X',courseName:'Học phần khác',credits:6},{courseCode:'UNPLANNED',courseName:'Khóa luận khác',credits:6}];
export const group={groupCode:'FINAL',kind:'GRADUATION',requiredCredits:6,completionOptions:[
  {optionCode:'THESIS',courseCodes:['T']},{optionCode:'CAPSTONE',courseCodes:['P1','P2']}]};
export const tree={semesters:[{semesterNumber:8,courses:catalog.filter(c=>c.courseCode!=='UNPLANNED')}],requirementGroups:[group,
  {groupCode:'CORE',kind:'MANDATORY_COMMON',groupName:'Đại cương',eligibleCourseCodes:['M']},
  {groupCode:'FREE',kind:'ELECTIVE_FREE',groupName:'Tự chọn chung',eligibleCourseCodes:['E']}]};

test('Vietnamese option labels preserve internal codes and support arbitrary existing codes',()=>{
  assert.equal(graduationLabel('THESIS'),'Khóa luận tốt nghiệp (THESIS)');
  assert.equal(graduationLabel('CAPSTONE'),'Đồ án/Chuyên đề tốt nghiệp (CAPSTONE)');
  assert.equal(graduationLabel('PA1'),'Phương án PA1');assert.equal(group.completionOptions[0].optionCode,'THESIS');
  assert.match(treeValidationMessage('Phương án THESIS có mã học phần lặp: HP-THESIS'),/Khóa luận tốt nghiệp \(THESIS\).*HP-THESIS$/);
});
test('graduation alternatives are not individual debts, while independent mandatory courses remain required',()=>{
  const optional={status:'NOT_STUDIED',graduationAlternative:true,requiredForCompletion:false};
  assert.match(curriculumCourseStatus(optional),/Lựa chọn tốt nghiệp/);assert.equal(curriculumCourseNeedsAttention(optional),false);
  assert.equal(curriculumCourseStatus({...optional,graduationGroupComplete:true}),'Không bắt buộc hoàn thành thêm');
  assert.match(curriculumCourseStatus({...optional,status:'FAILED',graduationGroupComplete:true}),/phương án khác/);
  assert.equal(curriculumCourseNeedsAttention({...optional,requiredForCompletion:true}),true);
});
test('two-option sentence is restricted to two valid canonical alternatives',()=>{
  assert.equal(graduationGuide(group,tree,catalog),'Sinh viên cần hoàn thành 1 trong 2 phương án tốt nghiệp.');
  const invalid={...group,completionOptions:[group.completionOptions[0],{optionCode:'CAPSTONE',courseCodes:[]}]};
  assert.match(graduationGuide(invalid,tree,catalog),/Nhóm đang cấu hình 2 phương án/);
  assert.match(graduationGuide({...group,completionOptions:[]},tree,catalog),/Chưa có/);
  assert.match(graduationGuide({...group,countsTowardGraduation:false},tree,catalog),/không được tính/);
  assert.match(graduationGuide({...group,completionOptions:[...group.completionOptions,{optionCode:'OTHER',courseCodes:['X']}]},tree,catalog),/3 phương án/);
});
test('summaries use catalog credits and the real planned semester for multi-course capstone',()=>{
  const summary=graduationSummary(group.completionOptions[1],group,tree,catalog);
  assert.equal(summary.total,6);assert.equal(summary.required,6);assert.equal(summary.state,'ready');
  assert.deepEqual(summary.semesters,[8]);assert.deepEqual(summary.rows.map(c=>c.credits),[3,3]);
  assert.deepEqual(summary.rows.map(c=>c.courseName),['Chuyên đề tốt nghiệp 1','Đồ án tốt nghiệp']);
});
test('short, surplus and missing credits are distinguished without displaying fake totals',()=>{
  assert.equal(graduationSummary({optionCode:'CUSTOM',courseCodes:['P1']},group,tree,catalog).state,'short');
  assert.equal(graduationSummary({optionCode:'CUSTOM',courseCodes:['T','P1']},group,tree,catalog).state,'check');
  const missing=graduationSummary({optionCode:'CUSTOM',courseCodes:['UNKNOWN']},group,tree,catalog);
  assert.equal(missing.total,null);assert.match(missing.issues.join(' '),/danh mục/);
});
test('duplicates within an option do not inflate the displayed total, and stored data is untouched',()=>{
  const option={optionCode:'CUSTOM',courseCodes:['P1','P1']};
  const summary=graduationSummary(option,group,tree,catalog);
  assert.equal(summary.total,3);assert.equal(summary.state,'check');assert.match(summary.issues.join(' '),/Môn lặp/);
  assert.deepEqual(option.courseCodes,['P1','P1']);
});
test('sharing courses between alternatives in the same group is not an incompatible group warning',()=>{
  assert.equal(graduationConflict(tree,'FINAL','T'),'');
  assert.equal(graduationSummary({optionCode:'OTHER',courseCodes:['T']},group,tree,catalog).state,'ready');
  assert.equal(graduationConflict({...tree,requirementGroups:[...tree.requirementGroups,{groupCode:'INFO',kind:'CONDITIONAL',eligibleCourseCodes:['T']}]},'FINAL','T'),'');
  assert.ok(!treeWarnings({...tree,requirementGroups:[...tree.requirementGroups,{groupCode:'INFO',kind:'CONDITIONAL',eligibleCourseCodes:['T']}]}).some(w=>w.includes('nhiều nhóm')));
});
test('mandatory and elective conflicts are warnings that name the actual course and group',()=>{
  for(const code of ['M','E']) {
    const summary=graduationSummary({optionCode:'OTHER',courseCodes:[code]},group,tree,catalog);
    assert.equal(summary.state,'check');assert.ok(summary.issues.some(i=>i.startsWith(code+':')));
  }
  assert.match(graduationConflict(tree,'FINAL','E'),/nhóm tự chọn: Tự chọn chung/);
});
test('missing or duplicate planned semesters require review',()=>{
  assert.match(graduationSummary({optionCode:'OTHER',courseCodes:['UNPLANNED']},group,tree,catalog).issues.join(' '),/học kỳ dự kiến/);
  const t={...tree,semesters:[{semesterNumber:0,courses:[catalog[0]]}]};
  assert.equal(graduationSummary(group.completionOptions[0],group,t,catalog).state,'check');
});
test('suggestions prioritize saved choices, names and tree membership, and do not modify the catalog',()=>{
  const thesis=suggestedGraduationCourses(catalog,tree,group,group.completionOptions[0]);
  assert.equal(thesis[0].courseCode,'T');assert.ok(!thesis.some(c=>c.courseCode==='UNPLANNED'));
  assert.ok(suggestedGraduationCourses(catalog,tree,group,{optionCode:'THESIS',courseCodes:['UNPLANNED']}).some(c=>c.courseCode==='UNPLANNED'));
  const capstone=suggestedGraduationCourses(catalog,tree,group,{optionCode:'CAPSTONE',courseCodes:[]});
  assert.ok(capstone.findIndex(c=>c.courseCode==='P1')<capstone.findIndex(c=>c.courseCode==='T'));
  assert.ok(!thesis.some(c=>c.courseCode==='M'));assert.ok(catalog.some(c=>c.courseCode==='M'));
  assert.equal(graduationCatalog(tree,catalog).find(c=>c.courseCode==='P1').semesterNumber,8);
  assert.equal(catalog[1].semesterNumber,undefined);
});
