import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {createServer} from 'vite';
import react from '@vitejs/plugin-react';
import {selectTranscript,highestAttempts,semesterText,numberText} from '../src/student-transcript.js';
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
  const html=await render();for(const text of ['GPA tích lũy hệ 4','Tín chỉ tích lũy đã đạt','Lập trình','Không đạt','Học lại','sr-mobile-cards','Đăng học'])assert.ok(html.includes(text),text);
  assert.doesNotMatch(html,/Chưa tính tổng tín chỉ|<pre|\[object Object\]/);
});
test('history dialog contains every attempt even when highest mode is selected',async()=>{
  const html=await render({detail:rows[1],filters:{search:'',year:'all',term:'all',mode:'highest'}});
  assert.match(html,/role="dialog"/);assert.match(html,/Lần 1/);assert.match(html,/Lần 2/);assert.match(html,/Hiển thị mọi lần học/);
});
test('missing metadata is explained once in a scoped warning and empty results have recovery',async()=>{
  const html=await render({data:{...data,issues:[{courseCode:'A',semesterCode:'2024-1',field:'credits',message:'Thiếu số tín chỉ hợp lệ trong danh mục.'}]}});
  assert.equal(html.split('Thiếu số tín chỉ hợp lệ trong danh mục.').length-1,1);
  assert.match(await render({filters:{search:'no match',year:'all',term:'all',mode:'attempts'}}),/Xóa bộ lọc/);
});
