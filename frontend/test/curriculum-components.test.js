import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {createServer} from 'vite';
import react from '@vitejs/plugin-react';

let server;
before(async () => { server = await createServer({configFile: false, plugins: [react()],
  server: {middlewareMode: true, hmr: false, watch: null}, optimizeDeps: {noDiscovery: true, include: []}}); });
after(async () => { await server?.close(); });
async function render(file, props, name = 'default') {
  const module = await server.ssrLoadModule('/src/' + file + '.jsx');
  const Component = module[name];
  return renderToStaticMarkup(React.createElement(Component, props));
}
const program = {id: 'program', specializations: [{code: 'AA', name: 'Phần mềm'}, {code: 'BB', name: 'Hệ thống'}],
  specializationTrees: {AA: {semesters: [{semesterNumber: 1, courses: [{courseCode: 'A', courseName: 'Môn A'}]}]},
    BB: {semesters: [{semesterNumber: 3, courses: [{courseCode: 'B', courseName: 'Môn B'}]}]}}};

test('preview renders only the selected specialization and its planned semester', async () => {
  const a = await render('CurriculumTrackPreview', {curriculum: program, initialCode: 'AA'});
  const b = await render('CurriculumTrackPreview', {curriculum: program, initialCode: 'BB'});
  assert.match(a, /Môn A/); assert.doesNotMatch(a, /Môn B/);
  assert.match(b, /Môn B/); assert.doesNotMatch(b, /Môn A/); assert.match(b, /Học kỳ 3/);
});

test('preview and assignment have readable empty and loading states', async () => {
  assert.match(await render('CurriculumTrackPreview', {curriculum: {specializations: []}}), /chưa có chuyên ngành/i);
  const assignment = await render('StudentAssignment', {token: 'test', request: async () => [], notify: () => {}});
  assert.match(assignment, /Đang tải ngành/); assert.match(assignment, /Gán CTĐT và chuyên ngành/);
  assert.doesNotMatch(assignment, /\[object Object\]/);
  const workspace = await render('CurriculumTreeWorkspace', {token: 'test', request: async () => [], notify: () => {}});
  assert.match(workspace, /Đang tải CTĐT/); assert.doesNotMatch(workspace, />DRAFT<|>ACTIVE</);
});

test('student summary renders official elective surplus, recognized quota and fixed identity', async () => {
  const html = await render('StudentCurriculumSummary', {data: {curriculumName: 'CTĐT 2024', majorId: 'major',
    specializationCode: 'AA', cohort: 'K2024', version: 2, electivePassedCredits: 9,
    groupProgress: [{kind: 'ELECTIVE_FREE', requiredCredits: 5, recognizedCredits: 5, complete: true}]},
    majors: [{id: 'major', name: 'Công nghệ thông tin', specializations: [{code: 'AA', name: 'Phần mềm'}]}]});
  for (const text of ['Công nghệ thông tin', 'Phần mềm', 'K2024', 'CTĐT 2024', '9', '5 / 5', 'Không bắt buộc']) assert.ok(html.includes(text), text);
  assert.doesNotMatch(html, /ELECTIVE_FREE/);
});

test('student summary distinguishes an incomplete elective requirement', async () => {
  const html = await render('StudentCurriculumSummary', {data: {electivePassedCredits: 2,
    groupProgress: [{kind: 'ELECTIVE_FREE', requiredCredits: 5, recognizedCredits: 2, complete: false}]}});
  assert.match(html, /đang thiếu yêu cầu/); assert.match(html, /bù môn không đạt/);
});

test('student curriculum separates loading from an account without an assigned program', async () => {
  const loading = await render('StudentCurriculumSummary', {loading: true}, 'StudentCurriculumState');
  const missing = await render('StudentCurriculumSummary', {unassigned: true}, 'StudentCurriculumState');
  assert.match(loading, /Đang tải CTĐT/); assert.doesNotMatch(loading, /chưa được gán/);
  assert.match(missing, /Bạn chưa được gán CTĐT/); assert.match(missing, /Giáo vụ cần gán/);
});

test('replacement details show active rules and saved historical evidence without technical attempt identifiers', async () => {
  const historical = {oldCourseCodes: ['OLD'], newCourses: [{courseCode: 'NEW', courseName: 'Môn nguồn', credits: 3}],
    recognized: true, historicalEvidence: true, attemptIds: {NEW: 'internal-attempt-id'}, grade4: 3.5, versionNumber: 1};
  const saved = await render('ReplacementEvidenceDetails', {rules: [historical]});
  assert.match(saved, /Bằng chứng công nhận đã lưu/); assert.match(saved, /Môn nguồn/); assert.match(saved, /OLD/);
  assert.match(saved, /Đã công nhận/); assert.doesNotMatch(saved, /internal-attempt-id/);
  const active = await render('ReplacementEvidenceDetails', {rules: [{...historical, recognized: false, historicalEvidence: false, automaticRecognitionSupported: true}]});
  assert.match(active, /Đang áp dụng/); assert.doesNotMatch(active, /Bằng chứng công nhận đã lưu/);
});
