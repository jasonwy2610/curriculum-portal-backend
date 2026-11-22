import test from 'node:test';
import assert from 'node:assert/strict';
import {selectTranscript} from '../src/student-transcript.js';

const course=courseCode=>({courseCode,courseName:'Tên '+courseCode,credits:3,state:'NOT_STUDIED'});
test('confirmed K2023 eight-term schedule produces eight complete tables, keeps all eighteen results and official totals',()=>{
  const codes=['2023-1','2023-2','2024-1','2024-2','2025-1','2025-2','2026-1','2026-2'];
  const data={summary:{cumulativeGpa4:2.64,academicEarnedCredits:54,graduationRecognizedCredits:52},issues:[],
    curriculumPlan:{available:true,semesters:codes.map((code,i)=>({semesterKey:'PLAN:'+(i+1),plannedSemesterNumber:i+1,academicSemesterCode:code,
      courses:Array.from({length:i===0?3:5},(_,j)=>course(`C${i}-${j}`))}))},
    semesters:codes.slice(0,7).map(code=>({semesterCode:code,academicYear:code.slice(0,4),gpa4:3,attemptedCredits:3,earnedCredits:3})),attempts:[]};
  let n=0;for(let i=0;i<7;i++)for(let j=0;j<(i===0?1:i<6?3:2);j++)data.attempts.push({recordId:'r'+n,attemptId:'a'+n++,courseCode:`C${i}-${j}`,
    semesterCode:codes[i],academicYear:codes[i].slice(0,4),score10:8,score4:3,status:'PASSED'});
  const before=JSON.stringify(data),v=selectTranscript(data);
  assert.equal(data.attempts.length,18);assert.equal(v.groups.length,8);assert.ok(v.groups.every(g=>!g.planned));
  assert.equal(v.groups[0].rows.length,3);assert.equal(v.groups[0].rows.filter(r=>!r.hasActualResult).length,2);
  assert.equal(v.groups.flatMap(g=>g.rows).filter(r=>r.hasActualResult).length,18);
  assert.equal(v.groups[7].gpa4,null);assert.equal(v.groups[7].attemptedCredits,0);
  assert.equal(selectTranscript(data,{term:'2026-2'}).groups.length,1);
  data.semesters.push({semesterCode:'2025-3',academicYear:'2025',gpa4:4,attemptedCredits:3,earnedCredits:3});
  data.attempts.push({...data.attempts[0],recordId:'summer',attemptId:'summer',semesterCode:'2025-3',academicYear:'2025',retake:true});
  const summer=selectTranscript(data);assert.equal(summer.groups.length,9);assert.equal(summer.groups.find(g=>g.semesterCode==='2025-3').rows.length,1);
  data.semesters.pop();data.attempts.pop();assert.equal(JSON.stringify(data),before);
});
function fixture(mapping){return {summary:{cumulativeGpa4:3,academicEarnedCredits:3,graduationRecognizedCredits:3},issues:[],
  curriculumPlan:{available:true,cohort:'K2023',semesters:[{semesterKey:'PLAN:1',plannedSemesterNumber:1,academicSemesterCode:mapping,courses:['A','B','C','D','E'].map(course)},
    {semesterKey:'PLAN:2',plannedSemesterNumber:2,courses:[course('F')]}]},
  semesters:[{semesterCode:'2025-3',academicYear:'2025',gpa4:3,attemptedCredits:3,earnedCredits:3,attemptCount:1}],
  attempts:[{recordId:'r',attemptId:'a',courseCode:'A',courseName:'Tên A',credits:3,semesterCode:'2025-3',academicYear:'2025',attemptNumber:1,score10:7,score4:3,status:'PASSED'}]};}
test('without an official calendar, one list shows every course and each real attempt exactly once',()=>{
  const data=fixture(),before=JSON.stringify(data),v=selectTranscript(data),rows=v.groups.flatMap(g=>g.rows);
  assert.equal(new Set(rows.map(r=>r.courseCode)).size,6);
  assert.equal(rows.filter(r=>r.attemptId==='a').length,1);
  assert.equal(v.groups[0].semesterCode,'2025-3');
  assert.equal(v.groups.find(g=>g.semesterCode==='PLAN:1').rows.length,4);
  assert.ok(v.groups.filter(g=>g.planned).every(g=>g.academicYear===null));
  assert.equal(JSON.stringify(data),before);
});
test('only an explicit academic calendar merges a five-course plan into its actual summer period',()=>{
  const data=fixture('2025-3'),v=selectTranscript(data),g=v.groups.find(g=>g.semesterCode==='2025-3');
  assert.equal(g.rows.length,5);assert.equal(g.plannedCourseCount,5);assert.equal(g.resultCourseCount,1);
  assert.equal(g.gpa4,3);assert.equal(g.attemptedCredits,3);assert.equal(g.earnedCredits,3);
  assert.ok(g.rows.filter(r=>!r.hasActualResult).every(r=>r.score10==null&&r.attemptNumber==null));
  assert.ok(!v.groups.some(g=>g.semesterCode==='PLAN:1'));
});
test('early, late and repeated attempts stay in their real periods; a planned reference is never a new attempt',()=>{
  const data=fixture('2023-1');data.attempts.push({...data.attempts[0],attemptId:'a2',semesterCode:'2026-1',academicYear:'2026',attemptNumber:2,score10:9,score4:4,retake:true});
  data.semesters.push({semesterCode:'2026-1',academicYear:'2026',gpa4:4,attemptedCredits:3,earnedCredits:3,attemptCount:1});
  const v=selectTranscript(data),g=v.groups.find(g=>g.semesterCode==='2023-1');
  assert.equal(g.rows.length,5);assert.equal(g.attemptCount,0);assert.equal(g.attemptedCredits,0);
  assert.equal(g.rows.find(r=>r.courseCode==='A').state,'HAS_HISTORY_ELSEWHERE');
  assert.equal(g.rows.find(r=>r.courseCode==='A').passedElsewhere,true);
  assert.deepEqual(v.groups.flatMap(g=>g.rows).filter(r=>r.hasActualResult).map(r=>r.semesterCode),['2025-3','2026-1']);
  assert.equal(selectTranscript(data,{mode:'highest'}).groups.flatMap(g=>g.rows).filter(r=>r.hasActualResult).length,1);
});
test('empty, non-eight and invalid-calendar plans do not invent years, scores or GPA',()=>{
  const data=fixture('2023-4');data.attempts=[];data.semesters=[];
  const v=selectTranscript(data);assert.equal(v.groups.length,2);assert.equal(v.rows.length,0);
  assert.equal(v.groups.reduce((n,g)=>n+g.rows.length,0),6);
  assert.ok(v.groups.every(g=>g.planned&&g.gpa4===null&&g.attemptedCredits===0));
  assert.ok(!v.years.includes('2023'));
});
test('filtering by an unmapped planned period finds its real history without moving it',()=>{
  const v=selectTranscript(fixture(),{term:'PLAN:1'});
  assert.equal(v.rows.length,1);assert.equal(v.rows[0].semesterCode,'2025-3');
  assert.deepEqual(v.groups.map(g=>g.semesterCode),['2025-3','PLAN:1']);
});
