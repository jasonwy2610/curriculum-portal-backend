import test from 'node:test';
import assert from 'node:assert/strict';
import {editableTrack, copyTrack, confirmTreeSwitch, canPublishTree, programStatus, groupedTree,
  courseRequirement, treeWarnings, assignTreeCourse, removeTreeCourse, assignmentCandidates, assignmentPayload,
  electiveProgress, treeValidationMessage} from '../src/curriculum-ui.js';
import {curriculumCourseStatus, curriculumCourseNeedsAttention} from '../src/curriculum-tracks.js';
import {requestJson} from '../src/api-request.js';

const group = (code, kind, courses) => ({groupCode: code, kind, eligibleCourseCodes: courses, requiredCredits: 3});
const tree = (code, semester) => ({semesters: [{semesterNumber: semester,
  courses: [{courseCode: code, type: 'MANDATORY'}, {courseCode: 'E', type: 'ELECTIVE'}]}],
  requirementGroups: [group('CORE', 'MANDATORY_COMMON', [code]), group('FREE', 'ELECTIVE_FREE', ['E'])]});
const program = {id: 'assigned', majorId: 'major', cohort: 'K2024', status: 'DRAFT',
  specializations: [{code: 'AA', name: 'Công nghệ phần mềm'}, {code: 'BB', name: 'Hệ thống thông tin'}],
  specializationTrees: {AA: tree('A', 1), BB: tree('B', 3)}};
const student = {id: 'student', majorId: 'major', cohort: 'K2024'};

test('switching admin trees preserves separate semester plans without mutating either tree', () => {
  const a = editableTrack(program, 'AA'), b = editableTrack(program, 'BB');
  a.semesters[0].courses[0].courseName = 'Changed locally';
  assert.equal(b.semesters[0].semesterNumber, 3);
  assert.equal(b.semesters[0].courses[0].courseCode, 'B');
  assert.equal(program.specializationTrees.AA.semesters[0].courses[0].courseName, undefined);
  assert.deepEqual(a.requirementGroups[0].specializationCodes, ['AA']);
});

test('unsaved tree switching requires confirmation and cancel leaves the current edit intact', () => {
  let confirmations = 0;
  assert.equal(confirmTreeSwitch(false, () => { confirmations++; return false; }), true);
  assert.equal(confirmTreeSwitch(true, () => { confirmations++; return false; }), false);
  assert.equal(confirmTreeSwitch(true, () => { confirmations++; return true; }), true);
  assert.equal(confirmations, 2);
});

test('copying a tree rewrites scopes only in the target draft and never shares mutable references', () => {
  const copied = copyTrack(program, 'BB', program, 'AA');
  assert.equal(copied.semesters[0].semesterNumber, 3);
  assert.deepEqual(copied.requirementGroups[0].specializationCodes, ['AA']);
  copied.semesters[0].courses.pop();
  assert.equal(program.specializationTrees.BB.semesters[0].courses.length, 2);
  assert.throws(() => copyTrack(program, 'AA', {...program, status: 'ACTIVE'}, 'AA'), /bản nháp/);
  assert.throws(() => copyTrack(program, 'AA', program, 'UNKNOWN'), /Chuyên ngành đích/);
  assert.throws(() => copyTrack({...program, specializationTrees: {AA: tree('A', 1)}}, 'BB', program, 'AA'), /thiếu cây/);
});

test('publish gating uses the whole backend validation and always locks published versions', () => {
  assert.equal(canPublishTree(program, false, {valid: true}), true);
  assert.equal(canPublishTree(program, true, {valid: true}), false);
  assert.equal(canPublishTree(program, false, {valid: false}), false);
  assert.equal(canPublishTree(program, false, null), false);
  assert.equal(canPublishTree({...program, status: 'ACTIVE'}, false, {valid: true}), false);
  assert.equal(programStatus('DRAFT'), 'Bản nháp');
  assert.equal(programStatus('ACTIVE'), 'Đã công bố');
});

test('courses are grouped by planned semester and requirement kind; structural warnings include context', () => {
  const selected = editableTrack(program, 'AA');
  const view = groupedTree(selected);
  assert.equal(view[0].semesterNumber, 1);
  assert.deepEqual(view[0].sections.map(s => s.label), ['Bắt buộc chung', 'Tự chọn']);
  assert.deepEqual(treeWarnings(selected), []);
  selected.semesters.push({semesterNumber: 3, courses: [{courseCode: 'A'}]});
  assert.match(treeWarnings(selected)[0], /Học kỳ 3.*A.*học kỳ 1/);
  selected.requirementGroups.push(group('FREE2', 'ELECTIVE_SPECIALIZATION', ['E']));
  assert.match(treeWarnings(selected).at(-1), /đúng một nhóm/);
});

test('assigning a course to an elective group moves membership and does not alter grade data', () => {
  const selected = editableTrack(program, 'AA');
  const moved = assignTreeCourse(selected, 'A', 'FREE');
  assert.equal(courseRequirement(moved, 'A').kind, 'ELECTIVE_FREE');
  assert.equal(moved.semesters[0].courses[0].type, 'ELECTIVE');
  assert.deepEqual(selected.requirementGroups[0].eligibleCourseCodes, ['A']);
  assert.deepEqual(moved.requirementGroups[0].eligibleCourseCodes, []);
});

test('removing a draft course clears group and graduation-option references without changing the source tree', () => {
  const selected = editableTrack(program, 'AA');
  selected.requirementGroups.push({groupCode: 'FINAL', kind: 'GRADUATION', completionOptions: [{optionCode: 'PA1', courseCodes: ['A']}]});
  const removed = removeTreeCourse(selected, 'A');
  assert.equal(removed.semesters[0].courses.some(c => c.courseCode === 'A'), false);
  assert.deepEqual(removed.requirementGroups[0].eligibleCourseCodes, []);
  assert.deepEqual(removed.requirementGroups.at(-1).completionOptions[0].courseCodes, []);
  assert.equal(selected.semesters[0].courses[0].courseCode, 'A');
});

test('elective progress displays backend recognition, quota and missing credit without recalculating selection', () => {
  const data = {electivePassedCredits: 9, groupProgress: [{kind: 'ELECTIVE_FREE', requiredCredits: 5,
    recognizedCredits: 5, complete: true, actualSelectedCredits: 7}]};
  assert.deepEqual(electiveProgress(data), {passed: 9, recognized: 5, required: 5, missing: 0, complete: true});
  assert.equal(electiveProgress(null), null);
  data.groupProgress[0].recognizedCredits = 2; data.groupProgress[0].complete = false;
  assert.equal(electiveProgress(data).missing, 3);
});

test('failed electives compensated by others keep failure visible without a repeat obligation', () => {
  const failed = {status: 'FAILED', requiredForCompletion: false, electiveGroupComplete: true};
  assert.equal(curriculumCourseNeedsAttention(failed), false);
  assert.equal(curriculumCourseStatus(failed), 'Không đạt · Không bắt buộc hoàn thành thêm');
  assert.equal(curriculumCourseStatus({...failed, status: 'NOT_STUDIED'}), 'Không bắt buộc hoàn thành thêm');
  assert.equal(failed.status, 'FAILED');
  assert.match(curriculumCourseStatus({status: 'FAILED'}), /Đang thiếu yêu cầu/);
});

test('student assignment filters by major and cohort and never offers another fixed version', () => {
  const published = {...program, status: 'ACTIVE'};
  assert.deepEqual(assignmentCandidates([program, published], student), [published]);
  const fixed = {...student, curriculumId: program.id, chosenSpecializationCode: 'AA'};
  assert.deepEqual(assignmentCandidates([published, {...published, id: 'new-version'}], fixed), [published]);
  assert.deepEqual(assignmentPayload(student, published, 'AA'), {curriculumId: 'assigned', chosenSpecializationCode: 'AA'});
  assert.throws(() => assignmentPayload(fixed, published, 'BB'), /cố định/);
  assert.throws(() => assignmentPayload(student, {...published, majorId: 'wrong'}, 'AA'), /không phù hợp/);
  assert.throws(() => assignmentPayload(student, {...published, cohort: 'K2025'}, 'AA'), /không phù hợp/);
  assert.throws(() => assignmentPayload(null, null, ''), /Chọn sinh viên/);
});

test('assignment requests send the fixed scope and expose backend business errors', async () => {
  const original = globalThis.fetch;
  const payload = assignmentPayload(student, {...program, status: 'ACTIVE'}, 'AA');
  try {
    globalThis.fetch = async (url, options) => {
      assert.match(url, /student\/curriculum-assignment$/);
      assert.equal(options.method, 'PUT'); assert.equal(options.headers.Authorization, 'Bearer test-token');
      assert.deepEqual(JSON.parse(options.body), payload);
      return new Response(JSON.stringify({success: false, message: 'CTĐT không thuộc ngành và khóa tuyển sinh của sinh viên'}), {status: 400});
    };
    await assert.rejects(requestJson('/api/v1/admin/students/student/curriculum-assignment', {token: 'test-token', method: 'PUT', body: payload}), /ngành và khóa/);
  } finally { globalThis.fetch = original; }
});

test('validation messages show the specialization name and readable requirement labels', () => {
  const message = treeValidationMessage('[AA] Môn E: requiredCredits / ELECTIVE', program);
  assert.match(message, /Chuyên ngành Công nghệ phần mềm/);
  assert.doesNotMatch(message, /requiredCredits|ELECTIVE/);
});
