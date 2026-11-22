import test from 'node:test';
import assert from 'node:assert/strict';
import {emptyTree, addSemester, addCourses, treeCourses, editCourse, deriveMandatory, prerequisiteReason, builderWarnings, validationLocation} from '../src/curriculum-builder.js';
import {assignTreeCourse, copyTrack, removeTreeCourse} from '../src/curriculum-ui.js';
const catalog = [{courseCode:'A',credits:2,courseName:'Môn A'}, {courseCode:'B',credits:3,courseName:'Môn B'}, {courseCode:'C',credits:4}];
const prepared = () => addCourses(emptyTree('AA'),1,['A','B'],catalog,'COMMON');

test('new specialization seeds separate required groups and exactly one elective pool', () => {
  const a=emptyTree('AA'), b=emptyTree('BB');
  assert.deepEqual(a.semesters,[{semesterNumber:1,courses:[]}]);
  assert.equal(a.requirementGroups.filter(g=>g.kind.startsWith('ELECTIVE')).length,1);
  a.requirementGroups[0].eligibleCourseCodes.push('A');
  assert.equal(b.requirementGroups[0].eligibleCourseCodes.length,0);
  assert.deepEqual(b.requirementGroups[0].specializationCodes,['BB']);
});
test('bulk add uses catalog metadata once per tree, reuses catalog across specializations', () => {
  const a=addCourses(prepared(),2,['A','C','C'],catalog,'FREE');
  assert.deepEqual(treeCourses(a).map(c=>c.courseCode),['A','B','C']);
  assert.equal(a.requirementGroups[0].requiredCredits,5);
  assert.equal(treeCourses(a).find(c=>c.courseCode==='C').type,'ELECTIVE');
  assert.equal(catalog[2].type,undefined);
  assert.equal(treeCourses(addCourses(emptyTree('BB'),1,['A'],catalog,'COMMON')).length,1);
});
test('empty semesters survive moving and deleting the last course', () => {
  const tree=addSemester(prepared(),7), moved=editCourse(tree,'A',{semesterNumber:3});
  const removed=removeTreeCourse(removeTreeCourse(moved,'B'),'A');
  assert.deepEqual(removed.semesters.map(s=>s.semesterNumber),[1,3,7]);
  assert.throws(()=>addSemester(tree,1.5),/số nguyên/);
  assert.throws(()=>addSemester(tree,0),/số nguyên/);
});
test('moving a course between groups derives mandatory credits without changing elective quota or flags', () => {
  const t=prepared();t.requirementGroups[2].requiredCredits=4;t.requirementGroups[0].countsTowardGpa=false;
  const next=deriveMandatory(assignTreeCourse(t,'B','FREE'),catalog);
  assert.equal(next.requirementGroups[0].requiredCredits,2);
  assert.equal(next.requirementGroups[2].requiredCredits,4);
  assert.equal(next.requirementGroups[0].countsTowardGpa,false);
  assert.deepEqual(t.requirementGroups[0].eligibleCourseCodes,['A','B']);
  assert.equal(treeCourses(next).find(c=>c.courseCode==='B').type,'ELECTIVE');
});
test('prerequisite picker rejects self, unplanned courses and same or later semester', () => {
  const t=editCourse(prepared(),'B',{semesterNumber:2});
  assert.match(prerequisiteReason(t,'B','B'),/tự/);
  assert.match(prerequisiteReason(t,'B','C'),/Chưa nằm/);
  assert.match(prerequisiteReason(t,'A','B'),/học kỳ trước/);
  assert.equal(prerequisiteReason(t,'B','A'),'');
  assert.match(prerequisiteReason(prepared(),'B','A'),/học kỳ trước/);
});
test('cycle detection follows transitive historical dependencies even before semester validation', () => {
  let t=addCourses(prepared(),3,['C'],catalog,'SPECIAL');
  t=editCourse(t,'A',{prerequisiteCourseCodes:['B']});t=editCourse(t,'B',{prerequisiteCourseCodes:['C']});
  assert.match(prerequisiteReason(t,'C','A'),/vòng lặp/);
  assert.match(builderWarnings(t).join(' '),/Học kỳ 1/);
});
test('removing a prerequisite preserves dependent configuration and exposes an actionable warning', () => {
  let t=editCourse(prepared(),'B',{semesterNumber:2,prerequisiteCourseCodes:['A']});
  t=removeTreeCourse(t,'A');
  assert.deepEqual(treeCourses(t)[0].prerequisiteCourseCodes,['A']);
  assert.match(builderWarnings(t).join(' '),/Học kỳ 2 · B · A: Chưa nằm/);
});
test('copy tree remaps scope and keeps graduation alternatives, quota and flags independent', () => {
  const t=prepared();t.requirementGroups.push({groupCode:'GRAD',kind:'GRADUATION',requiredCredits:3,countsTowardGpa:false,
    specializationCodes:['AA'],completionOptions:[{optionCode:'PA1',courseCodes:['B']},{optionCode:'PA2',courseCodes:['B']}]});
  const p={status:'DRAFT',specializations:[{code:'AA'},{code:'BB'}],specializationTrees:{AA:t}};
  const copied=copyTrack(p,'AA',p,'BB');
  assert.deepEqual(copied.requirementGroups.at(-1).specializationCodes,['BB']);
  assert.equal(copied.requirementGroups.at(-1).countsTowardGpa,false);
  copied.requirementGroups.at(-1).completionOptions[0].courseCodes.push('C');
  assert.deepEqual(t.requirementGroups.at(-1).completionOptions[0].courseCodes,['B']);
});
test('review locates backend course errors in all relevant specialization trees', () => {
  assert.equal(validationLocation('Mon A khong hop le',{AA:prepared()},[{code:'AA',name:'Phần mềm'}]),'Phần mềm · Học kỳ 1 · A');
  const specs=[{code:'AA',name:'Phần mềm'},{code:'BB',name:'Hệ thống'}];
  assert.equal(validationLocation('[BB] Mon A khong hop le',{AA:prepared(),BB:prepared()},specs),'Hệ thống · Học kỳ 1 · A');
  assert.equal(validationLocation('Ma A1 khong hop le',{AA:prepared()},specs),'');
});
