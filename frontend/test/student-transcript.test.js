import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {createServer} from 'vite';
import react from '@vitejs/plugin-react';
import {selectTranscript,highestAttempts,semesterText,numberText,transcriptPlanState} from '../src/student-transcript.js';
const rows=[{recordId:'r',attemptId:'a1',courseCode:'A',courseName:'Lập trình',credits:3,semesterCode:'2024-1',academicYear:'2024',term:1,attemptNumber:1,score10:3,score4:0,letterGrade:'F',status:'FAILED'},
  {recordId:'r',attemptId:'a2',courseCode:'A',courseName:'Lập trình',credits:3,semesterCode:'2025-1',academicYear:'2025',term:1,attemptNumber:2,score10:9,score4:4,letterGrade:'A+',status:'PASSED',retake:true}];
const data={summary:{cumulativeGpa4:4,academicEarnedCredits:3,graduationRecognizedCredits:2},attempts:rows,semesters:[{semesterCode:'2024-1',academicYear:'2024',gpa4:0,attemptedCredits:3,earnedCredits:0,attemptCount:1},{semesterCode:'2025-1',academicYear:'2025',gpa4:4,attemptedCredits:3,earnedCredits:3,attemptCount:1}],issues:[]};
test('search matches catalog names and year/semester filters retain actual retake history',()=>{
  assert.equal(selectTranscript(data,{search:'lập TRÌNH'}).rows.length,2);
  const v=selectTranscript(data,{year:'2024',term:'2024-1'});assert.equal(v.rows.length,1);assert.equal(v.failedCourses,1);assert.equal(v.selectedSemester.gpa4,0);
  assert.equal(selectTranscript(data).failedCourses,0);
});
test('highest-score mode chooses globally before applying period filters and does not destroy history',()=>{
  assert.equal(highestAttempts(rows)[0].attemptId,'a2');assert.equal(selectTranscript(data,{mode:'highest',year:'2024'}).rows.length,0);
  assert.equal(selectTranscript(data,{mode:'highest',year:'2024'}).failedCourses,0);
  assert.equal(selectTranscript(data,{mode:'highest'}).rows.length,1);assert.equal(data.attempts.length,2);
});
test('missing values stay unknown and term labels never expose raw enum',()=>{
  assert.equal(numberText(null,2),'—');assert.equal(numberText(0,2),'0,00');assert.match(semesterText('2024-3'),/Học kỳ hè/);
  assert.equal(semesterText('UNKNOWN'),'Chưa xác định học kỳ');
  assert.equal(highestAttempts([{courseCode:'PF',status:'PASSED',score10:null},{courseCode:'PF',status:'FAILED',score10:null}])[0].status,'PASSED');
});
let server;
before(async()=>{server=await createServer({configFile:false,plugins:[react()],server:{middlewareMode:true,hmr:false,watch:null},optimizeDeps:{noDiscovery:true,include:[]}})});
after(async()=>server?.close());
async function render(props){const {TranscriptView}=await server.ssrLoadModule('/src/StudentTranscript.jsx');return renderToStaticMarkup(React.createElement(TranscriptView,{data,filters:{search:'',year:'all',term:'all',mode:'attempts'},setFilters:()=>{},expanded:['2024-1','2025-1'],setExpanded:()=>{},detail:null,setDetail:()=>{},onReload:()=>{},...props}));}
test('view renders catalog, official summary, compact semester groups and mobile cards',async()=>{
  const html=await render();for(const text of ['GPA tích lũy hệ 4','Tín chỉ tích lũy đã đạt','Lập trình','Không đạt','Học lại','sr-mobile-cards','Học thực tế'])assert.ok(html.includes(text),text);
  assert.doesNotMatch(html,/Chưa tính tổng tín chỉ|<pre|\[object Object\]/);
});
test('history dialog contains every attempt even when highest mode is selected',async()=>{
  const html=await render({detail:rows[1],filters:{search:'',year:'all',term:'all',mode:'highest'}});
  assert.match(html,/role="dialog"/);assert.match(html,/Lần 1/);assert.match(html,/Lần 2/);assert.match(html,/Tất cả lần học/);
});
test('missing metadata is explained once in a scoped warning and empty results have recovery',async()=>{
  const html=await render({data:{...data,issues:[{courseCode:'A',semesterCode:'2024-1',field:'credits',message:'Thiếu số tín chỉ hợp lệ trong danh mục.'}]}});
  assert.equal(html.split('Thiếu số tín chỉ hợp lệ trong danh mục.').length-1,1);
  assert.match(await render({filters:{search:'no match',year:'all',term:'all',mode:'attempts'}}),/Xóa bộ lọc/);
});

const withPlan={...data,curriculumPlan:{available:true,message:'Kỳ dự kiến chưa có ánh xạ năm học thực tế.',version:3,
  semesters:Array.from({length:8},(_,i)=>({semesterKey:'PLAN:'+(i+1),plannedSemesterNumber:i+1,courses:[
    {courseCode:i===7?'A':'P'+(i+1),courseName:i===7?'Lập trình':'Môn dự kiến '+(i+1),credits:3,
      state:i===7?'HAS_HISTORY':i===2?'GROUP_COMPLETE':'NOT_STUDIED',actualSemesterCodes:i===7?['2024-1','2025-1']:[],replacementCourseCodes:[]}]}))}};
test('eight planned terms and actual years use independent keys, with no hidden empty periods or invented years',()=>{
  const v=selectTranscript(withPlan);
  assert.equal(v.groups.length,9);assert.equal(v.groups.filter(g=>g.planned).length,7);
  assert.deepEqual(v.groups.filter(g=>!g.planned).map(g=>g.semesterCode),['2024-1','2025-1']);
  assert.deepEqual(v.years,['2024','2025']);assert.ok(v.groups.filter(g=>g.planned).every(g=>g.academicYear===null&&g.gpa4===null&&g.attemptedCredits===0&&g.earnedCredits===0));
  assert.equal(v.rows.length,2);assert.equal(withPlan.summary.cumulativeGpa4,4);
});
test('planned empty term remains selectable with unchanged official cumulative GPA',()=>{
  const v=selectTranscript(withPlan,{term:'PLAN:5'});
  assert.equal(v.groups.length,1);assert.equal(v.groups[0].rows[0].state,'NOT_STUDIED');assert.equal(v.selectedSemester.gpa4,null);
  assert.equal(v.groups[0].attemptedCredits,0);assert.equal(withPlan.summary.cumulativeGpa4,4);
  assert.equal(selectTranscript(withPlan,{year:'2024'}).groups.length,1);
  assert.equal(selectTranscript(withPlan,{year:'planned'}).groups.length,7);
  assert.equal(selectTranscript(withPlan,{term:'PLAN:5',search:'not found'}).groups.length,1);
});
test('highest mode does not hide an actual period whose best attempt occurred elsewhere',()=>{
  const v=selectTranscript(withPlan,{mode:'highest',term:'2024-1'});
  assert.equal(v.groups.length,1);assert.equal(v.groups[0].rows.length,0);assert.equal(v.groups[0].attemptCount,1);assert.equal(v.groups[0].gpa4,0);
});
test('non-eight plan with no grades keeps all configured periods and has no invented attempts',()=>{
  const empty={...withPlan,attempts:[],semesters:[],curriculumPlan:{...withPlan.curriculumPlan,semesters:withPlan.curriculumPlan.semesters.slice(0,5)}};
  assert.equal(selectTranscript(empty).groups.length,5);assert.equal(selectTranscript(empty).rows.length,0);
});
test('view renders every term without accordion buttons and separates reference rows from actual scores',async()=>{
  const html=await render({data:withPlan});
  assert.equal((html.match(/class="sr-term-heading"/g)??[]).length,9);
  assert.doesNotMatch(html,/aria-expanded|Mở tất cả|Thu gọn tất cả/);
  assert.match(html,/Học kỳ dự kiến 8/);assert.match(html,/Chưa có kết quả/);assert.match(html,/Nhóm yêu cầu đã hoàn thành/);
  assert.match(html,/Dự kiến: kỳ 8/);assert.doesNotMatch(html,/sr-period-type/);
  assert.doesNotMatch(html,/đây không phải yêu cầu học lại|Kỳ dự kiến chưa có ánh xạ|Đầy đủ học phần theo kế hoạch|không cộng tín chỉ kế hoạch|Năm học chưa xác định/);
  assert.doesNotMatch(html,/sr-term-context|class="sr-plan-note"/);
});
test('plan references to recognized replacements open real source history without a fictitious target attempt',async()=>{
  const row={courseCode:'OLD',courseName:'Môn gốc',credits:3,planned:true,state:'RECOGNIZED_REPLACEMENT',replacementCourseCodes:['A'],actualSemesterCodes:[]};
  const html=await render({data:withPlan,detail:row});
  assert.match(html,/Môn nguồn: A/);assert.match(html,/Lần 1/);assert.match(html,/Lần 2/);
  assert.equal(withPlan.attempts.length,2);
});

const mixedPlan={...withPlan,curriculumPlan:{...withPlan.curriculumPlan,semesters:[
  {semesterKey:'PLAN:1',plannedSemesterNumber:1,courses:[
    {courseCode:'A',courseName:'Lập trình',credits:3,state:'HAS_HISTORY',actualSemesterCodes:['2024-1','2025-1']},
    {courseCode:'B',courseName:'Cơ sở dữ liệu',credits:4,state:'NOT_STUDIED'},
    {courseCode:'C',courseName:'Tự chọn',credits:2,state:'GROUP_COMPLETE'}]},
  {semesterKey:'PLAN:2',plannedSemesterNumber:2,courses:[
    {courseCode:'D',courseName:'Mạng máy tính',credits:3,state:'NOT_STUDIED'},
    {courseCode:'E',courseName:'Hệ điều hành',credits:3,state:'NOT_STUDIED'}]}]}};

test('planned tables join real grades beside every ungraded course without changing actual semesters or totals',()=>{
  const before=JSON.stringify(mixedPlan),v=selectTranscript(mixedPlan,{term:'PLAN:1'}),planned=v.groups.flatMap(g=>g.rows);
  assert.deepEqual(planned.map(r=>r.courseCode),['A','A','B','C']);
  assert.deepEqual(planned.filter(r=>r.hasActualResult).map(r=>[r.semesterCode,r.attemptNumber,r.score10,r.status]),
    [['2024-1',1,3,'FAILED'],['2025-1',2,9,'PASSED']]);
  for(const row of planned.filter(r=>!r.hasActualResult))for(const field of ['attemptNumber','score10','score4','letterGrade'])assert.equal(row[field],undefined);
  assert.equal(new Set(planned.map(r=>r.rowKey)).size,4);
  assert.equal(v.rows.length,2);assert.equal(v.selectedSemester.gpa4,null);
  assert.deepEqual(selectTranscript(mixedPlan).groups.filter(g=>!g.planned).map(g=>g.rows.map(r=>r.attemptId)),[['a1'],['a2']]);
  assert.equal(JSON.stringify(mixedPlan),before);
});

test('highest view fills the planned row with the official best attempt and keeps unstudied subjects',()=>{
  const v=selectTranscript(mixedPlan,{term:'PLAN:1',mode:'highest'});
  const visible=v.groups.flatMap(g=>g.rows);assert.deepEqual(visible.map(r=>r.courseCode),['A','B','C']);
  assert.equal(visible[0].score10,9);assert.equal(visible[0].semesterCode,'2025-1');
  assert.equal(mixedPlan.attempts.length,2);assert.equal(mixedPlan.summary.cumulativeGpa4,4);
});

test('an entirely ungraded semester shows every planned subject with blank grades and no failed status',async()=>{
  const v=selectTranscript(mixedPlan,{term:'PLAN:2'});
  assert.deepEqual(v.groups[0].rows.map(r=>r.courseCode),['D','E']);assert.equal(v.rows.length,0);
  const html=await render({data:mixedPlan,filters:{search:'',year:'all',term:'PLAN:2',mode:'attempts'}});
  assert.match(html,/Mạng máy tính/);assert.match(html,/Hệ điều hành/);assert.match(html,/Chưa có kết quả/);
  assert.doesNotMatch(html,/Không đạt|NaN|undefined/);
  assert.match(html,/<td>—<\/td><td>—<\/td><td>—<\/td><td>—<\/td>/);
});

test('mixed planned table renders graded and blank rows together, preserving elective and retake labels',async()=>{
  const html=await render({data:mixedPlan,filters:{search:'',year:'all',term:'PLAN:1',mode:'attempts'}});
  for(const text of ['Lập trình','Cơ sở dữ liệu','Tự chọn','9,0','3,0','Học lại','Nhóm yêu cầu đã hoàn thành','Chưa có kết quả'])assert.ok(html.includes(text),text);
  assert.match(html,/<td>2<\/td><td>9,0<\/td><td>4,0<\/td><td>A\+<\/td>/);
});

test('the actual old-server response is diagnosed as a missing API contract, not a missing student assignment',async()=>{
  const state=transcriptPlanState(data);
  assert.equal(state.problem,true);assert.match(state.message,/cấu trúc cũ/);
  const html=await render();assert.match(html,/role="alert"/);assert.match(html,/cập nhật máy chủ/);
  assert.match(html,/Lập trình/);assert.doesNotMatch(html,/Chưa có thông tin cây CTĐT/);
});

test('five planned subjects with only one academic record still render five rows and four blank results',async()=>{
  const fixture={...data,attempts:[rows[1]],curriculumPlan:{...withPlan.curriculumPlan,semesters:[{semesterKey:'PLAN:1',plannedSemesterNumber:1,academicSemesterCode:'2025-1',
    courses:['A','B','C','D','E'].map(courseCode=>({courseCode,courseName:'Môn '+courseCode,credits:3,state:courseCode==='A'?'HAS_HISTORY':'NOT_STUDIED'}))}]}};
  const v=selectTranscript(fixture,{term:'PLAN:1'});
  assert.equal(v.groups[0].rows.length,5);assert.equal(v.groups[0].rows.filter(r=>r.hasActualResult).length,1);
  assert.equal(v.groups[0].rows.filter(r=>!r.hasActualResult).length,4);
  assert.equal(transcriptPlanState(fixture).problem,false);
  const html=await render({data:fixture,filters:{search:'',year:'all',term:'PLAN:1',mode:'attempts'}});
  assert.equal((html.match(/<tr>/g)??[]).length,6);assert.doesNotMatch(html,/cấu trúc cũ|Chưa có thông tin cây CTĐT/);
});

test('malformed plan JSON produces a clear error instead of silently hiding courses or crashing',()=>{
  for(const plan of [{available:true},{available:true,semesters:[{semesterKey:'PLAN:1',courses:{}}]}]){
    const value={...data,curriculumPlan:plan};assert.equal(transcriptPlanState(value).problem,true);
    assert.equal(selectTranscript(value).groups.filter(g=>g.planned).length,0);
  }
});
