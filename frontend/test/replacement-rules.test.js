import {test} from 'node:test';
import assert from 'node:assert/strict';
import {requestJson} from '../src/api-request.js';
import {ruleWindows, ruleStatus, replacementCourses, vietnamDateTimeInput, vietnamDateTimeIso}
  from '../src/replacement-rules.js';

const before = '2026-12-01T00:00:00.000Z';
const transition = '2026-12-02T00:00:00.000Z';
const end = '2026-12-03T00:00:00.000Z';
const first = {id: 'a', status: 'ACTIVE', lifecycleStatus: 'PUBLISHED', versionNumber: 1,
  publishedAt: before, effectiveDate: before, majorId: 'major', applicableCohorts: ['K2024'],
  oldCourseCodes: ['OLD'], newCourseCodes: ['NEW']};
const next = {...first, id: 'b', ruleFamilyId: 'a', versionNumber: 2,
  effectiveDate: transition, applicableCohorts: ['K2025']};

test('drafts and cancelled schedules keep the previous window active', () => {
  const draft = {...next, status: 'INACTIVE', lifecycleStatus: 'DRAFT'};
  const draftWindows = ruleWindows([first, draft]);
  assert.equal(ruleStatus(draft, draftWindows, Date.parse(transition)), 'DRAFT');
  assert.equal(ruleStatus(first, draftWindows, Date.parse(end)), 'ACTIVE');
  const cancelled = {...next, endsAt: before};
  const cancelledWindows = ruleWindows([first, cancelled]);
  assert.equal(ruleStatus(cancelled, cancelledWindows, Date.parse(before)), 'INACTIVE');
  assert.equal(ruleStatus(first, cancelledWindows, Date.parse(end)), 'ACTIVE');
});

test('scheduled and retired versions use precise start and exclusive end times', () => {
  const retired = {...next, endsAt: end};
  const windows = ruleWindows([first, retired]);
  assert.equal(windows.get(first.id).end, Date.parse(transition));
  assert.equal(ruleStatus(first, windows, Date.parse(transition) - 1), 'ACTIVE');
  assert.equal(ruleStatus(retired, windows, Date.parse(transition) - 1), 'SCHEDULED');
  assert.equal(ruleStatus(first, windows, Date.parse(transition)), 'INACTIVE');
  assert.equal(ruleStatus(retired, windows, Date.parse(transition)), 'ACTIVE');
  assert.equal(ruleStatus(retired, windows, Date.parse(end)), 'INACTIVE');
});

test('replacement selection uses the actual completion moment and applicable cohort', () => {
  const semester = {courses: [{courseCode: 'OLD', specializationCodes: ['IT']}]};
  const curriculum = {semesters: [semester]};
  const student = {majorId: 'major', cohort: 'K2024', chosenSpecializationCode: 'IT'};
  assert.deepEqual([...replacementCourses([first, next], student, curriculum, semester, before)], ['NEW']);
  assert.equal(replacementCourses([first, next], student, curriculum, semester, transition).size, 0);
  assert.equal(replacementCourses([first], {...student, chosenSpecializationCode: 'OTHER'},
    curriculum, semester, before).size, 0);
  assert.equal(replacementCourses([first], student, curriculum, semester, null).size, 0);
});

test('completion and retirement timestamps round-trip in Vietnam time', () => {
  const value = '2026-12-01T07:00:01';
  const iso = vietnamDateTimeIso(value);
  assert.equal(iso, '2026-12-01T00:00:01.000Z');
  assert.equal(vietnamDateTimeInput(iso), value);
  assert.equal(vietnamDateTimeIso(''), null);
});

test('business errors retain the backend message instead of reporting connection loss', async t => {
  const message = 'Quy tắc độc lập xung đột trên cùng môn gốc, khóa và thời gian áp dụng';
  t.mock.method(globalThis, 'fetch', async () => Response.json({success: false, message}, {status: 400}));
  await assert.rejects(requestJson('/validation'), {message, title: 'Thông tin chưa hợp lệ'});
});

test('transport failures and invalid HTTP responses get distinct feedback', async t => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => { throw new TypeError('offline'); });
  await assert.rejects(requestJson('/validation'), {title: 'Không thể kết nối máy chủ'});
  fetch.mock.mockImplementation(async () => new Response('<html/>'));
  await assert.rejects(requestJson('/validation'), {title: 'Không thể đọc phản hồi'});
});

test('retirement serializes the confirmation payload and unwraps the response', async t => {
  const body = {endsAt: end, reason: 'Hết kế hoạch chuyển tiếp'};
  const fetch = t.mock.method(globalThis, 'fetch', async () => Response.json({success: true, data: {id: 'b', ...body}}));
  assert.deepEqual(await requestJson('/retire', {method: 'POST', token: 'token', body}), {id: 'b', ...body});
  assert.equal(fetch.mock.calls[0].arguments[1].headers.Authorization, 'Bearer token');
  assert.deepEqual(JSON.parse(fetch.mock.calls[0].arguments[1].body), body);
});
