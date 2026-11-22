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

test('preview and tree workspace have readable empty and loading states', async () => {
  assert.match(await render('CurriculumTrackPreview', {curriculum: {specializations: []}}), /chưa có chuyên ngành/i);
  const workspace = await render('CurriculumTreeWorkspace', {token: 'test', request: async () => [], notify: () => {}});
  assert.match(workspace, /Đang tải CTĐT/); assert.doesNotMatch(workspace, />DRAFT<|>ACTIVE</);
});

test('course picker exposes labeled checkboxes, disabled reasons and an explicit apply action', async () => {
  const html = await render('CoursePicker', {label:'Tiên quyết', items:[
    {courseCode:'A',courseName:'Môn A',credits:3}, {courseCode:'B',courseName:'Môn B',credits:2}],
    value:['A'], reasonFor:c=>c.courseCode==='B'?'Tiên quyết phải ở học kỳ trước':'', onApply:()=>{}});
  assert.match(html,/type="checkbox" checked/); assert.match(html,/type="checkbox" disabled/);
  assert.match(html,/Tiên quyết phải ở học kỳ trước/); assert.match(html,/Áp dụng lựa chọn/);
  assert.match(html,/Đã chọn 1 học phần/); assert.doesNotMatch(html,/<select[^>]*multiple|giữ Ctrl/);
});

test('graduation cards explain alternatives in Vietnamese without opening the catalog or rewriting codes', async () => {
  const catalog=[{courseCode:'T',courseName:'Khóa luận phần mềm',credits:6},{courseCode:'P1',courseName:'Chuyên đề 1',credits:3},{courseCode:'P2',courseName:'Chuyên đề 2',credits:3}];
  const group={groupCode:'FINAL',kind:'GRADUATION',requiredCredits:6,completionOptions:[
    {optionCode:'THESIS',courseCodes:['T']},{optionCode:'CAPSTONE',courseCodes:['P1','P2']}]};
  const tree={semesters:[{semesterNumber:8,courses:catalog}],requirementGroups:[group]};
  const html=await render('GraduationOptions',{group,tree,catalog,onChange:()=>{}});
  for(const text of ['Khóa luận tốt nghiệp (THESIS)','Đồ án/Chuyên đề tốt nghiệp (CAPSTONE)','1 trong 2','6/6','Học kỳ 8','Chọn học phần'])assert.ok(html.includes(text),text);
  assert.doesNotMatch(html,/Tìm mã hoặc tên học phần|<select[^>]*multiple/);
  assert.equal(group.completionOptions[0].optionCode,'THESIS');
  const locked=await render('GraduationOptions',{group,tree,catalog,readOnly:true});
  assert.doesNotMatch(locked,/<button|<input|<select/);assert.match(locked,/Đồ án\/Chuyên đề/);
});

test('graduation picker renders live selected credits, planned semester and conflict warnings', async () => {
  const html=await render('CoursePicker',{label:'Phương án CAPSTONE',items:[{courseCode:'P1',courseName:'Chuyên đề 1',credits:3,semesterNumber:8},
    {courseCode:'P2',courseName:'Chuyên đề 2',credits:3,semesterNumber:8}],value:['P1','P2'],onApply:()=>{},showSelectionCredits:true,showSemester:true,
    warningFor:c=>c.courseCode==='P2'?'Đang thuộc nhóm tự chọn':''});
  assert.match(html,/Tổng 6 tín chỉ/);assert.match(html,/Học kỳ 8/);assert.match(html,/Đang thuộc nhóm tự chọn/);
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
  assert.match(missing, /Bạn chưa được gán CTĐT/); assert.match(missing, /kiểm tra thông tin/);
  assert.doesNotMatch(missing, /Giáo vụ cần gán/);
});

test('replacement details show active rules and saved historical evidence without technical attempt identifiers', async () => {
  const historical = {oldCourseCodes: ['OLD'], newCourses: [{courseCode: 'NEW', courseName: 'Môn nguồn', credits: 3}],
    recognized: true, historicalEvidence: true, attemptIds: {NEW: 'internal-attempt-id'}, grade4: 3.5, versionNumber: 1};
  const saved = await render('ReplacementEvidenceDetails', {rules: [historical]});
  assert.match(saved, /Bằng chứng công nhận đã lưu/); assert.match(saved, /Môn nguồn/); assert.match(saved, /OLD/);
  assert.match(saved, /Đã công nhận/); assert.doesNotMatch(saved, /internal-attempt-id/);
  assert.match(saved, /Công nhận tín chỉ theo môn gốc/);
  const active = await render('ReplacementEvidenceDetails', {rules: [{...historical, recognized: false, historicalEvidence: false, automaticRecognitionSupported: true}]});
  assert.match(active, /Đang áp dụng/); assert.doesNotMatch(active, /Bằng chứng công nhận đã lưu/);
});

test('stored academic identity remains visible without planned assignment or repair actions', async () => {
  const fixed=await render('AcademicIdentity',{student:{majorId:'m',cohort:'K2026',curriculumId:'p',chosenSpecializationCode:'AA'},
    curriculum:{curriculumName:'CTĐT đã lưu',version:1,specializations:[{code:'AA',name:'Phần mềm'}]},majors:[{id:'m',name:'Ngành CNTT'}]});
  for(const value of ['Ngành CNTT','K2026','Phần mềm','Phiên bản cố định','CTĐT đã lưu'])assert.ok(fixed.includes(value));
  const missing=await render('AcademicIdentity',{student:{cohort:'K2026'}});
  assert.match(missing,/Hồ sơ chưa có CTĐT/);assert.doesNotMatch(missing,/dự kiến|Gán cố định|<form|<select/);
});

test('compact graduation guidance keeps alternatives, credit deficits and group-conflict warnings',async()=>{
  const catalog=[{courseCode:'T',courseName:'Khóa luận',credits:6},{courseCode:'E',courseName:'Tự chọn',credits:3}];
  const group={groupCode:'G',kind:'GRADUATION',requiredCredits:6,completionOptions:[{optionCode:'THESIS',courseCodes:['T']},{optionCode:'CAPSTONE',courseCodes:['E']}]};
  const tree={semesters:[{semesterNumber:8,courses:catalog}],requirementGroups:[group,{groupCode:'EL',groupName:'Tự chọn chung',kind:'ELECTIVE_FREE',eligibleCourseCodes:['E']}]};
  const html=await render('GraduationOptions',{group,tree,catalog,readOnly:true});
  for(const text of ['Khóa luận tốt nghiệp','Đồ án/Chuyên đề tốt nghiệp','một phương án','độc lập với nhóm tự chọn','Đang thuộc nhóm tự chọn','Học kỳ 8','6/6','Cần kiểm tra'])assert.ok(html.includes(text),text);
  assert.doesNotMatch(html,/Backend|validation|Mã nội bộ|không phải phân loại chính thức/);
  assert.doesNotMatch(html,/<button|<input|<select/);
});

test('compact student state retains actionable assignment and elective guidance without duplicate GPA notes',async()=>{
  const missing=await render('StudentCurriculumSummary',{unassigned:true},'StudentCurriculumState');
  assert.match(missing,/Bạn chưa được gán CTĐT/);assert.match(missing,/liên hệ giáo vụ/);
  assert.doesNotMatch(missing,/Bạn không cần tự tạo CTĐT/);
  const complete=await render('StudentCurriculumSummary',{data:{electivePassedCredits:9,groupProgress:[{kind:'ELECTIVE_FREE',requiredCredits:5,recognizedCredits:5,complete:true}]}});
  assert.match(complete,/Không bắt buộc/);assert.match(complete,/5 \/ 5/);
  assert.doesNotMatch(complete,/GPA hiển thị theo kết quả tính chính thức/);
});


test('replacement modal separates learning, eligibility and saved recognition across all shapes',async()=>{
  const source=(courseCode,learningStatus)=>({courseCode,courseName:'Tên '+courseCode,credits:3,learningStatus});
  for(const [olds,news,label] of [[['O'],['N'],'1–1'],[['O'],['N','N2'],'1–N'],[['O','O2'],['N'],'N–1']]){
    const rule={oldCourseCodes:olds,newCourseCodes:news,oldCourses:olds.map(c=>source(c,'NOT_STUDIED')),
      newCourses:news.map(c=>source(c,'PASSED')),automaticRecognitionSupported:true,recognitionState:'ELIGIBLE'};
    const html=await render('ReplacementEvidenceDetails',{rules:[rule]});
    assert.match(html,new RegExp(label));for(const c of [...olds,...news])assert.ok(html.includes('Tên '+c));
    assert.match(html,/Đã đạt/);assert.match(html,/Đủ điều kiện xét công nhận/);assert.doesNotMatch(html,/Đã công nhận/);
    const historical=await render('ReplacementEvidenceDetails',{rules:[{...rule,historicalEvidence:true,recognized:true,recognitionState:'HISTORICAL_RECOGNIZED',automaticRecognitionSupported:false,recognizedAt:'2024-06-01T00:00:00Z'}]});
    assert.match(historical,/Bằng chứng công nhận đã lưu/);assert.match(historical,/Đã công nhận/);assert.match(historical,/Không áp dụng hiện tại/);
  }
  const missing=await render('ReplacementEvidenceDetails',{rules:[{oldCourseCodes:['O'],newCourses:[{courseCode:'N',credits:3,learningStatus:'FAILED'}],newCourseCodes:['N'],recognitionState:'NOT_RECOGNIZED',automaticRecognitionSupported:true}]});
  assert.match(missing,/Chưa có tên học phần/);assert.match(missing,/Không đạt/);assert.match(missing,/Chưa được công nhận/);
  assert.doesNotMatch(missing,/Thông tin thay thế học phần|<pre|\[object Object\]/);
});
