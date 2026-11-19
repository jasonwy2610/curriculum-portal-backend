import test from 'node:test';
import assert from 'node:assert/strict';
import {selectCurriculumTrack, curriculumCourseNeedsAttention, curriculumCourseStatus} from '../src/curriculum-tracks.js';
import {replacementCourses} from '../src/replacement-rules.js';

test('elective alternatives keep their real grade but do not become individual debts', () => {
  for (const status of ['FAILED', 'NOT_STUDIED']) {
    const course = {status, requiredForCompletion: false, electiveGroupComplete: true};
    assert.equal(curriculumCourseNeedsAttention(course), false);
    assert.equal(curriculumCourseStatus(course), status === 'FAILED'
      ? 'Không đạt · Không bắt buộc hoàn thành thêm' : 'Không bắt buộc hoàn thành thêm');
    assert.equal(course.status, status);
    assert.equal(curriculumCourseNeedsAttention({status}), true);
  }
  assert.match(curriculumCourseStatus({status: 'FAILED', requiredForCompletion: false}), /bù bằng môn khác/);
});

test('same catalogue course can have different semester and quota in each cohort tree', () => {
  const program = {specializationTrees: {
    AA: {semesters: [{semesterNumber: 1, courses: [{courseCode: 'SHARED'}]}], requirementGroups: []},
    BB: {semesters: [{semesterNumber: 3, courses: [{courseCode: 'SHARED'}]}], requirementGroups: []}
  }, creditPlansBySpecialization: {AA: {requiredTotal: 9, mandatoryCredits: 6}, BB: {requiredTotal: 12, mandatoryCredits: 9}}};
  assert.equal(selectCurriculumTrack(program, 'AA').semesters[0].semesterNumber, 1);
  assert.equal(selectCurriculumTrack(program, 'BB').semesters[0].semesterNumber, 3);
  assert.equal(selectCurriculumTrack(program, 'AA').totalCredits, 9);
  assert.equal(selectCurriculumTrack(program, 'BB').totalCredits, 12);
  assert.throws(() => selectCurriculumTrack(program, 'UNKNOWN'));
});

test('legacy scopes and new trees use the same projection for grade-entry replacement candidates', () => {
  const at = '2026-01-02T00:00:00Z';
  const rule = {id: 'rule', ruleType: '1-1', majorId: 'major', lifecycleStatus: 'PUBLISHED', status: 'ACTIVE',
    applicableCohorts: ['K2024'], oldCourseCodes: ['OLD'], newCourseCodes: ['NEW'],
    effectiveDate: '2026-01-01T00:00:00Z', publishedAt: '2026-01-01T00:00:00Z'};
  const program = {id: 'assigned', majorId: 'major', cohort: 'K2024', specializationTrees: {AA: {semesters: [{semesterNumber: 2, courses: [{courseCode: 'OLD'}]}]}}};
  const student = {curriculumId: 'assigned', majorId: 'major', cohort: 'K2024', chosenSpecializationCode: 'AA'};
  assert.deepEqual([...replacementCourses([rule], student, program, program.specializationTrees.AA.semesters[0], at)], ['NEW']);
  const legacy = {semesters: [{semesterNumber: 1, courses: [{courseCode: 'COMMON'}, {courseCode: 'OLD', specializationCodes: ['BB']}]}]};
  assert.deepEqual(selectCurriculumTrack(legacy, 'AA').semesters[0].courses.map(course => course.courseCode), ['COMMON']);
});

test('replacement suggestions reject wrong identity, malformed rules and elective sources for mandatory targets', () => {
  const semester = {semesterNumber: 1, courses: [{courseCode: 'OLD', type: 'MANDATORY'}, {courseCode: 'EL', type: 'ELECTIVE'}]};
  const student = {curriculumId: 'assigned', majorId: 'major', cohort: 'K2024', chosenSpecializationCode: 'AA'};
  const program = {id: 'assigned', majorId: 'major', cohort: 'K2024', specializationTrees: {AA: {semesters: [semester]}}};
  const rule = {id: 'rule', majorId: 'major', lifecycleStatus: 'PUBLISHED', status: 'ACTIVE',
    applicableCohorts: ['K2024'], oldCourseCodes: ['OLD'], newCourseCodes: ['EL', 'NEW'], ruleType: '1-N',
    effectiveDate: '2024-01-01T00:00:00Z', publishedAt: '2024-01-01T00:00:00Z'};
  const codes = (r, p = program) => [...replacementCourses([r], student, p, semester, '2024-06-01T00:00:00Z')];
  assert.deepEqual(codes(rule), []);
  const allowed = {...rule, ruleType: '1-1', newCourseCodes: ['NEW']};
  assert.deepEqual(codes(allowed), ['NEW']);
  assert.deepEqual(codes({...allowed, oldCourseCodes: []}), []);
  assert.deepEqual(codes(allowed, {...program, id: 'new-version'}), []);
  assert.deepEqual(codes(allowed, {...program, majorId: 'other-major'}), []);
  assert.deepEqual(codes(allowed, {...program, cohort: 'K2025'}), []);
});
