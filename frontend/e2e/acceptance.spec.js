import {test,expect} from '@playwright/test';
const fixtures=JSON.parse(process.env.PORTAL_E2E_FIXTURES);
const fixture=(info,shape='workflow')=>fixtures[info.project.name][shape];
async function login(page,f,role='admin'){
  await page.goto('/',{waitUntil:'domcontentloaded'});
  await page.getByPlaceholder('mssv@huflit.edu.vn').fill(f[role+'Email']);
  await page.getByPlaceholder('Nhập mật khẩu').fill(f.password);
  await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();
  await expect(page.locator('.sidebar')).toBeVisible();
  await expect(page.locator('.loading-line')).toHaveCount(0);
}
async function nav(page,name){await page.locator('.sidebar').getByRole('button',{name,exact:true}).click();}
async function api(page,path,method='GET',body){
  const response=await page.evaluate(async({path,method,body,base})=>{
    const token=JSON.parse(localStorage.getItem('huflit-session')).token;
    const r=await fetch(base+path,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
    const json=await r.json();return {status:r.status,data:json.data};
  },{path,method,body,base:process.env.VITE_API_BASE_URL});
  return response;
}
async function enterGrade(page,f,code,grade,semester='1',date='2024-06-01T07:00:00'){
  await nav(page,'Kết quả học tập');
  await page.getByRole('button',{name:'Quản lý lần học',exact:true}).click();
  await page.getByRole('button',{name:/Nhập.*điểm|Thêm.*lần học|Ghi nhận/}).click();
  const modal=page.locator('form.modal');
  await modal.getByLabel('Tìm sinh viên',{exact:true}).fill(f.studentCode);
  await modal.getByRole('combobox',{name:'Sinh viên',exact:true}).selectOption(f.studentId);
  await modal.getByRole('combobox',{name:'Học kỳ trong CTĐT',exact:true}).selectOption(semester);
  await modal.getByRole('combobox',{name:'Năm học thực tế',exact:true}).selectOption('2024');
  await modal.getByLabel('Ngày giờ hoàn thành thực tế (giờ Việt Nam)',{exact:true}).fill(date);
  await modal.getByRole('combobox',{name:'Học phần',exact:true}).selectOption(f.prefix+code);
  await modal.getByLabel('Điểm hệ 10 (0–10)',{exact:true}).fill(String(grade));
  const response=page.waitForResponse(r=>r.url().endsWith('/academic-records/attempts')&&r.request().method()==='POST');
  await modal.getByRole('button',{name:'Lưu dữ liệu',exact:true}).click();
  const saved=await response;
  expect(saved.status()).toBe(200);
  expect(saved.request().postDataJSON().attempt.completedAt).toBe(new Date(date+'+07:00').toISOString());
  await expect(modal).toHaveCount(0);
  await expect(page.getByText('Đã lưu kết quả học tập',{exact:true}).last()).toBeVisible();
}
test.beforeEach(async({page})=>{
  // Keep acceptance deterministic/offline for third-party fonts; application APIs remain real.
  await page.route('https://fonts.googleapis.com/**',route=>route.fulfill({contentType:'text/css',body:''}));
  const errors=[];page.__expectedHttp=new Set();page.on('pageerror',()=>errors.push('uncaught browser exception'));
  page.on('console',m=>{if(m.type()==='error'&&!m.text().includes('Failed to load resource'))
    errors.push(m.text().replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g,'[redacted token]').slice(0,250));});
  page.on('requestfailed',r=>{if(!r.failure()?.errorText.includes('ERR_ABORTED'))errors.push('failed request '+new URL(r.url()).pathname);});
  page.on('response',r=>{
    const path=new URL(r.url()).pathname;
    if(r.status()>=400 && !page.__expectedHttp.has(path+':'+r.status()) && !(r.status()===403&&path==='/api/v1/admin/students') && !(r.status()===400&&path==='/api/majors'))
      errors.push(`HTTP ${r.status()} ${path}`);
  });
  page.__errors=errors;
  page.on('dialog',dialog=>dialog.accept());
});
test.afterEach(async({page},info)=>{
  await page.screenshot({path:info.outputPath('final.png'),fullPage:true,timeout:10000});
  expect(page.__errors).toEqual([]);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'page must fit the viewport').toBe(true);
});

test('admin without manual assignment retains stored identity, tree publication, grades and student progress',async({page},info)=>{
  const f=fixture(info); await login(page,f);
  await page.goto('/assignments?page=assignments#assignments',{waitUntil:'domcontentloaded'});
  await expect(page.locator('h1')).toHaveText('Tổng quan');
  await expect(page.getByRole('button',{name:'Gán cố định',exact:true})).toHaveCount(0);
  await nav(page,'Ngành đào tạo');await expect(page.getByText('Integration major',{exact:true}).first()).toBeVisible();
  await nav(page,'Quản lý CTĐT');
  await page.getByRole('combobox',{name:'CTĐT và phiên bản',exact:true}).selectOption(f.programId);
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('BB');
  await expect(page.getByRole('combobox',{name:'Chuyên ngành',exact:true})).toHaveValue('BB');
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('AA');
  await page.getByRole('button',{name:'Kiểm tra toàn bộ cây',exact:true}).click();
  await expect(page.getByText('Tất cả cây hợp lệ.',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Công bố toàn bộ CTĐT',exact:true}).click();
  await expect(page.getByText('Phiên bản đã khóa. Tạo bản sao để thay đổi.',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'Gán CTĐT sinh viên',exact:true})).toHaveCount(0);
  const oldEndpoint='/api/v1/admin/students/'+f.studentId+'/curriculum-assignment';
  page.__expectedHttp.add(oldEndpoint+':410');
  const retired=await api(page,oldEndpoint,'PUT',{curriculumId:f.programId,chosenSpecializationCode:'BB'});
  expect(retired.status).toBe(410);
  const stored=await api(page,'/api/v1/admin/students?q='+encodeURIComponent(f.studentCode));
  expect(stored.data.items.find(s=>s.id===f.studentId).curriculumId).toBe(f.programId);
  expect(stored.data.items.find(s=>s.id===f.studentId).chosenSpecializationCode).toBe('AA');
  for(const [code,grade,semester,day] of [['C',7,'1',1],['S',8,'1',1],['E4',3,'2',1],['E3',7,'2',2],['E2',8,'2',3],['E4',10,'2',4]])
    await enterGrade(page,f,code,grade,semester,`2024-06-0${day}T07:00:00`);
  await page.getByRole('button',{name:'Sửa điểm có lý do',exact:true}).click();
  await page.getByPlaceholder('Tìm mã số / tên / email').fill(f.studentCode);
  await page.getByRole('button',{name:'Tìm sinh viên',exact:true}).click();
  await page.getByRole('combobox',{name:'Chọn sinh viên',exact:true}).selectOption(f.studentId);
  await page.getByRole('button',{name:'Xem kết quả học tập',exact:true}).click();
  await page.getByRole('combobox',{name:'Học phần',exact:true}).selectOption({label:f.prefix+'C (1 lần học)'});
  await page.getByRole('combobox',{name:'Lần học',exact:true}).selectOption({index:1});
  await page.getByLabel('Điểm mới',{exact:true}).fill('8');
  await page.getByLabel('Lý do chỉnh sửa',{exact:true}).fill('Local E2E verified correction');
  await page.getByRole('button',{name:'Lưu sửa điểm',exact:true}).click();
  await expect(page.getByText('Đã sửa điểm và lưu lịch sử',{exact:true})).toBeVisible();
  await nav(page,'Quản lý CTĐT');
  const selectedProgram=page.getByRole('combobox',{name:'CTĐT và phiên bản',exact:true});
  await selectedProgram.selectOption(f.programId); // Re-selecting the retained version must not erase its tree.
  await expect(page.getByText('Phiên bản đã khóa. Tạo bản sao để thay đổi.',{exact:true})).toBeVisible();
  await page.getByLabel('Mã CTĐT cho phiên bản mới',{exact:true}).fill(f.prefix+'V2');
  await page.getByRole('button',{name:'Tạo phiên bản nháp mới',exact:true}).click();
  await expect(page.getByText('Lưu bản nháp trước khi kiểm tra và công bố.',{exact:true})).toBeVisible();
  await nav(page,'Đăng xuất');await login(page,f,'student');await nav(page,'CTĐT của tôi');
  await expect(page.getByText('Không thể tải CTĐT')).toHaveCount(0);
  const result=await api(page,'/api/v1/student/curriculum/evaluation-preview');
  expect(result.status).toBe(200);expect(result.data.curriculumId).toBe(f.programId);
  expect(result.data.recognizedCredits).toBe(11);expect(result.data.gpaCredits).toBe(15);
  expect(result.data.groupProgress.find(g=>g.groupCode==='FREE').recognizedCourseCodes).toEqual([f.prefix+'E3',f.prefix+'E2']);
  expect(result.data.cumulativeGpa4).toBe(3.53);
  await expect(page.locator('.sidebar').getByRole('button',{name:'Quản lý CTĐT',exact:true})).toHaveCount(0);
  const denied=await api(page,'/api/v1/admin/students');expect(denied.status).toBe(403);
  await nav(page,'Kết quả học tập');
  await expect(page.getByRole('heading',{name:'Bảng điểm của bạn',exact:true})).toBeVisible();
  const transcript=await api(page,'/api/v1/student/academic-records/transcript');
  expect(transcript.status).toBe(200);expect(transcript.data.summary.academicEarnedCredits).toBe(15);
  expect(transcript.data.summary.graduationRecognizedCredits).toBe(11);expect(transcript.data.summary.cumulativeGpa4).toBe(3.53);
  await page.getByRole('combobox',{name:'Học kỳ',exact:true}).selectOption('2024-1');
  await expect(page.getByText('2,79',{exact:true}).first()).toBeVisible();
  await expect(page.getByRole('button',{name:'Thu gọn tất cả',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Mở tất cả',exact:true})).toHaveCount(0);
  await expect(page.locator('.sr-term-heading').first()).toBeVisible();
  await page.evaluate(()=>window.scrollTo(0,0));
  await page.screenshot({path:info.outputPath('transcript.png'),fullPage:true});
  await page.getByPlaceholder('Mã hoặc tên học phần').fill('Integration E4');
  if(info.project.name==='desktop')await expect(page.getByRole('row').filter({hasText:f.prefix+'E4'})).toHaveCount(2);
  else await expect(page.locator('.sr-mobile-cards:visible article')).toHaveCount(2);
  await page.getByRole('combobox',{name:'Chế độ xem',exact:true}).selectOption('highest');
  await page.getByRole('button',{name:'Lịch sử '+f.prefix+'E4',exact:true}).click();
  await expect(page.getByRole('dialog').getByRole('listitem')).toHaveCount(2);
  await expect(page.getByRole('dialog').getByText('Không đạt',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Đóng lịch sử',exact:true}).click();
  await page.getByPlaceholder('Mã hoặc tên học phần').fill('NO_MATCH');
  await expect(page.getByText('Không có học phần phù hợp bộ lọc.',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Xóa bộ lọc',exact:true}).click();
});
for(const shape of ['1-1','1-N','N-1'])test(`replacement ${shape} through grade form and visible recognition`,async({page},info)=>{
  const f=fixture(info,shape);await login(page,f);
  await enterGrade(page,f,'NEW',7);
  if(shape==='1-N')await enterGrade(page,f,'ALT',8);
  await nav(page,'Đăng xuất');await login(page,f,'student');await nav(page,'CTĐT của tôi');
  const result=await api(page,'/api/v1/student/curriculum/evaluation-preview');
  expect(result.status).toBe(200);expect(result.data.recognizedCredits).toBe(shape==='N-1'?6:3);
  expect(result.data.recognizedReplacementRuleIds).toHaveLength(1);
  await page.locator('.sc-semester-trigger').first().click();
  await page.getByTitle('Xem thông tin thay thế').first().click();
  const modal=page.getByRole('dialog');
  await expect(modal.getByRole('heading',{name:'Thông tin học phần thay thế',exact:true})).toHaveCount(1);
  await expect(modal.locator('.replacement-course-list').nth(0).locator('li')).toHaveCount(shape==='N-1'?2:1);
  await expect(modal.locator('.replacement-course-list').nth(1).locator('li')).toHaveCount(shape==='1-N'?2:1);
  await expect(modal.locator('.replacement-course-list').nth(1)).toContainText('Đã đạt');
  await expect(modal).toContainText('Đã công nhận');await expect(modal).toContainText('tín chỉ');
  await page.screenshot({path:info.outputPath('replacement-'+shape+'.png'),fullPage:true});
  await modal.getByRole('button',{name:'Đóng',exact:true}).last().click();
  await nav(page,'Môn học thay thế');await expect(page.getByText(shape,{exact:true}).first()).toBeVisible();
  if(shape==='1-1'){
    await nav(page,'Đăng xuất');await login(page,f);
    const retired=await api(page,'/api/v1/admin/replacement-rules/'+result.data.recognizedReplacementRuleIds[0]+'/retire','POST',{reason:'Local browser historical verification'});
    expect(retired.status).toBe(200);
    await nav(page,'Đăng xuất');await login(page,f,'student');await nav(page,'CTĐT của tôi');
    await page.locator('.sc-semester-trigger').first().click();
    await page.getByTitle('Xem thông tin thay thế').first().click();
    await expect(page.getByRole('dialog').getByRole('heading',{name:'Bằng chứng công nhận đã lưu',exact:true})).toBeVisible();
    const history=await api(page,'/api/v1/student/curriculum/evaluation-preview');
    expect(history.data.recognizedCredits).toBe(3);expect(history.data.historicalRecognitionEvidence).toHaveLength(1);
    await page.getByRole('dialog').getByRole('button',{name:'Đóng',exact:true}).last().click();
  }
  await nav(page,'Kết quả học tập');
  const transcript=(await api(page,'/api/v1/student/academic-records/transcript')).data;
  const original=transcript.curriculumPlan.semesters.flatMap(s=>s.courses).find(c=>c.courseCode===f.prefix+'C');
  expect(original.state).toBe('RECOGNIZED_REPLACEMENT');expect(original.replacementCourseCodes).toContain(f.prefix+'NEW');
  expect(transcript.summary.graduationRecognizedCredits).toBe(shape==='N-1'?6:3);
  await expect(page.locator('.sr-planned-term').first()).toContainText('Đã công nhận qua môn thay thế');
});

test('loading, empty directory and business API error remain understandable',async({page},info)=>{
  const f=fixture(info);await login(page,f);
  await nav(page,'Kết quả học tập');
  await page.getByPlaceholder('Mã sinh viên, họ tên hoặc email...',{exact:true}).fill('NO_SUCH_LOCAL_STUDENT');
  await expect(page.getByText('Không tìm thấy sinh viên phù hợp.',{exact:true})).toBeVisible();
  await page.route('**/api/majors?*',async route=>{await new Promise(r=>setTimeout(r,1500));await route.fulfill({status:400,contentType:'application/json',body:JSON.stringify({success:false,message:'Dữ liệu kiểm thử chưa hợp lệ'})});});
  await nav(page,'Ngành đào tạo');await expect(page.getByText(/Đang tải/).first()).toBeVisible();
  await expect(page.getByText('Dữ liệu kiểm thử chưa hợp lệ').first()).toBeVisible();
  await expect(page.getByText('Không thể kết nối máy chủ',{exact:true})).toHaveCount(0);
});

test('transcript loading, empty, HTTP failure and genuinely missing metadata are scoped',async({page},info)=>{
  const f=fixture(info);await login(page,f,'student');let phase='empty';
  const path='/api/v1/student/academic-records/transcript';
  page.__expectedHttp.add(path+':503');
  await page.route('**'+path,async route=>{
    await new Promise(r=>setTimeout(r,700));
    if(phase==='empty')return route.fulfill({contentType:'application/json',body:JSON.stringify({success:true,data:{summary:{academicEarnedCredits:0},attempts:[],semesters:[],issues:[]}})});
    if(phase==='error')return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({success:false,message:'java.lang.InternalError: test'})});
    if(phase==='invalid')return route.fulfill({contentType:'application/json',body:JSON.stringify({success:true,data:{}})});
    const response=await route.fetch();const json=await response.json();const value=json.data;const code=value.attempts[0].courseCode;
    value.attempts=value.attempts.map(r=>r.courseCode===code?{...r,courseName:null,credits:null}:r);
    value.summary.cumulativeGpa4=null;value.summary.academicEarnedCredits=null;
    value.semesters=value.semesters.map(s=>value.attempts.some(r=>r.courseCode===code&&r.semesterCode===s.semesterCode)?{...s,gpa4:null,attemptedCredits:null,earnedCredits:null}:s);
    value.issues=[{courseCode:code,semesterCode:null,field:'credits',message:'Thiếu số tín chỉ hợp lệ trong danh mục.'}];
    return route.fulfill({response,json});
  });
  await nav(page,'Kết quả học tập');await expect(page.getByText('Đang tải bảng điểm...',{exact:true})).toBeVisible();
  await expect(page.getByText('Chưa có kết quả học tập',{exact:true})).toBeVisible();
  phase='error';await page.getByRole('button',{name:'Tải lại bảng điểm',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Chưa thể tải kết quả học tập',exact:true})).toBeVisible();
  await expect(page.getByText('java.lang.InternalError: test',{exact:true})).toHaveCount(0);
  phase='invalid';await page.getByRole('button',{name:'Thử lại',exact:true}).click();
  await expect(page.getByText('Dữ liệu bảng điểm chưa đầy đủ. Vui lòng thử lại hoặc liên hệ giáo vụ.',{exact:true})).toBeVisible();
  phase='missing';await page.getByRole('button',{name:'Thử lại',exact:true}).click();
  await page.locator('.sr-issues summary').click();
  await expect(page.locator('.sr-issues li').filter({hasText:'Thiếu số tín chỉ hợp lệ trong danh mục.'})).toHaveCount(1);
  await expect(page.locator('.sr-overview article').first().locator('strong')).toHaveText('—');
  await expect(page.locator('pre')).toHaveCount(0);
});
async function builderAdd(page,f,term,codes,group){
  const semester=page.locator('#cb-term-'+term);
  if(await semester.getAttribute('open')===null) await semester.locator(':scope > summary').click();
  await semester.getByRole('button',{name:'Thêm học phần vào kỳ '+term,exact:true}).click();
  const modal=page.getByRole('dialog',{name:'Thêm học phần vào kỳ '+term,exact:true});
  await modal.getByRole('combobox',{name:'Nhóm cho các học phần mới',exact:true}).selectOption(group);
  for(const code of codes){
    await modal.getByLabel('Tìm mã hoặc tên học phần',{exact:true}).fill(f.prefix+code);
    await modal.getByRole('checkbox',{name:new RegExp('^'+f.prefix+code+' ·')}).check();
    if(codes.length>1 && code===codes[0]){
      await modal.getByLabel('Tìm mã hoặc tên học phần',{exact:true}).fill(f.prefix);
      await modal.getByRole('button',{name:'Trang sau',exact:true}).click();
      await expect(modal.getByRole('status').first()).toContainText('Đã chọn 1 học phần');
      await modal.getByRole('button',{name:'Trang trước',exact:true}).click();
    }
  }
  await expect(modal.getByRole('status').first()).toContainText('Đã chọn '+codes.length+' học phần');
  if(codes.length>1){
    await modal.getByLabel('Tìm mã hoặc tên học phần',{exact:true}).fill(f.prefix);
    await page.screenshot({path:test.info().outputPath('course-picker.png'),fullPage:true});
  }
  await modal.getByRole('button',{name:'Thêm các học phần đã chọn',exact:true}).click();
  await expect(modal).toHaveCount(0);
}
test('curriculum builder creates, preserves two drafts, picks prerequisites and publishes all trees',async({page},info)=>{
  const f=fixture(info);await login(page,f);await nav(page,'Quản lý CTĐT');
  const original=(await api(page,'/api/curriculums/'+f.programId)).data;
  await page.getByRole('button',{name:'Tạo CTĐT mới',exact:true}).click();
  await page.getByLabel('Mã CTĐT',{exact:true}).fill(f.prefix+'UX');
  await page.getByLabel('Tên CTĐT',{exact:true}).fill('CTĐT quy trình bốn bước '+info.project.name);
  await page.getByRole('combobox',{name:'Ngành của CTĐT',exact:true}).selectOption(original.majorId);
  await page.getByLabel('Khóa tuyển sinh của CTĐT',{exact:true}).fill('K2024');
  await expect(page.getByRole('button',{name:'Mở CTĐT đã có',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Lưu bản nháp',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('Ngành và khóa đã có CTĐT.');
  await page.getByLabel('Khóa tuyển sinh của CTĐT',{exact:true}).fill('K2030');
  await page.getByLabel('Năm áp dụng',{exact:true}).fill('2030');
  await page.getByRole('checkbox',{name:'Track A',exact:true}).check();
  await page.getByRole('checkbox',{name:'Track B',exact:true}).check();
  await expect(page.getByLabel('Phiên bản',{exact:true})).toHaveAttribute('readonly','');
  await page.getByRole('button',{name:'2. Cây học phần',exact:true}).click();
  await builderAdd(page,f,1,['C'],'COMMON');
  await page.getByLabel('Học kỳ mới',{exact:true}).fill('2');
  await page.getByRole('button',{name:'Thêm học kỳ',exact:true}).click();
  await builderAdd(page,f,2,['S'],'SPECIAL');
  await builderAdd(page,f,2,['E2','E3'],'FREE');
  await page.getByRole('button',{name:'Chi tiết '+f.prefix+'S',exact:true}).click();
  const edit=page.getByRole('dialog',{name:'Chi tiết '+f.prefix+'S',exact:true});
  const search=edit.getByLabel('Tìm mã hoặc tên học phần',{exact:true});
  await search.fill(f.prefix+'S');
  await expect(edit.getByRole('checkbox',{name:new RegExp('^'+f.prefix+'S ·')})).toBeDisabled();
  await search.fill(f.prefix+'C');await edit.getByRole('checkbox',{name:new RegExp('^'+f.prefix+'C ·')}).check();
  await edit.getByRole('button',{name:'Áp dụng lựa chọn',exact:true}).click();
  await page.keyboard.press('Escape');await expect(edit).toHaveCount(0);
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('BB');
  await expect(page.locator('.cb-course')).toHaveCount(0);
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('AA');
  await expect(page.locator('.cb-course')).toHaveCount(4);
  await page.getByRole('button',{name:'3. Yêu cầu tín chỉ',exact:true}).click();
  await page.getByLabel('Chỉ tiêu tín chỉ tự chọn',{exact:true}).fill('4');
  await expect(page.getByRole('button',{name:'Thêm tự chọn',exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'Thêm phương án tốt nghiệp',exact:true}).click();
  await page.getByLabel('Tín chỉ yêu cầu của nhóm',{exact:true}).fill('3');
  await page.getByRole('button',{name:'2. Cây học phần',exact:true}).click();
  await page.getByLabel('Học kỳ mới',{exact:true}).fill('3');
  await page.getByRole('button',{name:'Thêm học kỳ',exact:true}).click();
  await builderAdd(page,f,3,['NEW'],{label:'Phương án tốt nghiệp'});
  // A cancel leaves all unsaved trees untouched.
  await page.getByRole('tab',{name:'Xây dựng và công bố',exact:true}).click();
  page.removeAllListeners('dialog');let warned=0;page.on('dialog',async d=>{warned++;await d.dismiss();});
  await nav(page,'Đăng xuất');await nav(page,'Tổng quan');expect(warned).toBe(2);await expect(page.locator('.cb-workspace')).toBeVisible();
  page.removeAllListeners('dialog');
  page.on('dialog',d=>d.accept());
  let creates=0;
  page.on('request',r=>{if(new URL(r.url()).pathname==='/api/curriculums'&&r.method()==='POST')creates++;});
  // One failed tree write must keep the other acknowledged tree and permit retry
  // without creating a second curriculum. The injected failure writes no data.
  let interrupted=false;
  await page.route('**/api/curriculums/*/specializations/BB/tree',async route=>{
    if(!interrupted && route.request().method()==='PUT'){
      interrupted=true;page.__expectedHttp.add(new URL(route.request().url()).pathname+':503');
      await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({success:false,message:'Không thể lưu cây trong lần thử kiểm tra này.'})});
    }else await route.continue();
  });
  await page.getByRole('button',{name:'Lưu bản nháp',exact:true}).click();
  await expect(page.locator('.cb-workspace [role="alert"]')).toContainText('Đã lưu một phần.');
  await expect(page.locator('.cb-course')).toHaveCount(5);
  await page.getByRole('button',{name:'Lưu bản nháp',exact:true}).click();
  await expect(page.getByText('Đã lưu bản nháp CTĐT và các cây chuyên ngành',{exact:true}).first()).toBeVisible();
  await expect(page.getByRole('combobox',{name:'Chuyên ngành',exact:true})).toHaveValue('AA');
  expect(creates).toBe(1);
  const created=(await api(page,'/api/curriculums')).data.find(p=>p.curriculumCode===(f.prefix+'UX').toUpperCase());expect(created).toBeTruthy();
  await page.getByRole('button',{name:'Kiểm tra toàn bộ cây',exact:true}).click();
  await expect(page.getByText('CTĐT chưa đủ điều kiện công bố.',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'Công bố toàn bộ CTĐT',exact:true})).toBeDisabled();
  // Removing a persisted draft specialization must use the existing delete API,
  // otherwise a metadata-only update leaves an orphan tree blocking publication.
  await page.getByRole('button',{name:'1. Thông tin CTĐT',exact:true}).click();
  await page.getByRole('checkbox',{name:'Track B',exact:true}).uncheck();
  const removed=page.waitForResponse(r=>r.url().endsWith('/specializations/BB')&&r.request().method()==='DELETE');
  await page.getByRole('button',{name:'Lưu bản nháp',exact:true}).click();expect((await removed).status()).toBe(200);
  await expect(page.getByText('Có thay đổi chưa lưu.',{exact:false})).toHaveCount(0);
  const single=(await api(page,'/api/curriculums/'+created.id)).data;
  expect(Object.keys(single.specializationTrees)).toEqual(['AA']);
  await page.getByRole('checkbox',{name:'Track B',exact:true}).check();
  await page.getByRole('button',{name:'Lưu bản nháp',exact:true}).click();
  await expect(page.getByText('Có thay đổi chưa lưu.',{exact:false})).toHaveCount(0);
  await page.getByRole('button',{name:'4. Kiểm tra và công bố',exact:true}).click();
  await page.getByRole('button',{name:'Xem cây Track B',exact:true}).click();
  await page.getByText('Sao chép cây từ CTĐT hoặc chuyên ngành khác',{exact:true}).click();
  await page.getByRole('combobox',{name:'CTĐT nguồn',exact:true}).selectOption(created.id);
  await page.getByRole('combobox',{name:'Chuyên ngành nguồn',exact:true}).selectOption('AA');
  await page.getByRole('button',{name:'Sao chép vào cây đang chọn',exact:true}).click();
  await expect(page.locator('.cb-course')).toHaveCount(5);
  await page.getByRole('button',{name:'Lưu bản nháp',exact:true}).click();
  await expect(page.getByText('Có thay đổi chưa lưu.',{exact:false})).toHaveCount(0);
  await page.getByRole('button',{name:'Kiểm tra toàn bộ cây',exact:true}).click();
  await expect(page.getByText('Tất cả cây hợp lệ.',{exact:true})).toBeVisible();
  await page.screenshot({path:info.outputPath('review.png'),fullPage:true});
  await page.getByRole('button',{name:'Công bố toàn bộ CTĐT',exact:true}).click();
  await expect(page.getByText('Phiên bản đã khóa. Tạo bản sao để thay đổi.',{exact:true})).toBeVisible();
  const saved=(await api(page,'/api/curriculums/'+created.id)).data;
  expect(saved.status).toBe('ACTIVE');
  for(const code of ['AA','BB']){
    const tree=saved.specializationTrees[code];expect(tree.semesters.flatMap(s=>s.courses)).toHaveLength(5);
    expect(tree.requirementGroups.find(g=>g.kind==='ELECTIVE_FREE').requiredCredits).toBe(4);
    expect(tree.semesters.flatMap(s=>s.courses).find(c=>c.courseCode===f.prefix+'S').prerequisiteCourseCodes).toEqual([f.prefix+'C']);
    expect(tree.requirementGroups.find(g=>g.kind==='GRADUATION').completionOptions[0].courseCodes).toEqual([f.prefix+'NEW']);
  }
  await page.getByRole('button',{name:'2. Cây học phần',exact:true}).click();
  await expect(page.getByRole('button',{name:/Thêm học phần vào kỳ/})).toHaveCount(0);
  await expect(page.locator('select[multiple]')).toHaveCount(0);
  for(const button of await page.getByRole('button',{name:'Đóng thông báo',exact:true}).all())
    if(await button.isVisible()) await button.click({timeout:1000}).catch(()=>{});
  await page.evaluate(()=>window.scrollTo(0,0));
});
const graduationCard=(page,code)=>page.locator('.go-card').filter({has:page.locator('summary b',{hasText:'('+code+')'})});
async function optionCheck(card,page,f,code,checked){
  await card.getByLabel('Tìm mã hoặc tên học phần',{exact:true}).fill(f.prefix+code);
  await card.getByRole('checkbox',{name:new RegExp('^'+f.prefix+code+' ·')}).setChecked(checked);
}
test('THESIS and CAPSTONE cards keep codes, support suggestions and recognize only one graduation quota',async({page},info)=>{
  const f=fixture(info,'graduation');await login(page,f);await nav(page,'Quản lý CTĐT');
  await page.getByRole('combobox',{name:'CTĐT và phiên bản',exact:true}).selectOption(f.programId);
  await page.getByRole('button',{name:'3. Yêu cầu tín chỉ',exact:true}).click();
  await expect(graduationCard(page,'THESIS').locator('.go-total')).toContainText('6/6 tín chỉ');
  await expect(page.getByRole('button',{name:'Chọn học phần',exact:true})).toHaveCount(0);
  await page.getByLabel('Mã CTĐT cho phiên bản mới',{exact:true}).fill(f.prefix+'G2');
  const cloned=page.waitForResponse(r=>r.url().endsWith('/clone')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'Tạo phiên bản nháp mới',exact:true}).click();
  const draft=(await (await cloned).json()).data;
  await page.getByRole('button',{name:'3. Yêu cầu tín chỉ',exact:true}).click();
  const thesis=graduationCard(page,'THESIS'), capstone=graduationCard(page,'CAPSTONE');
  await expect(page.getByText('Sinh viên cần hoàn thành 1 trong 2 phương án tốt nghiệp.',{exact:true})).toBeVisible();
  await expect(page.getByLabel('Tìm mã hoặc tên học phần',{exact:true})).toHaveCount(0);
  await expect(thesis).toContainText('Học kỳ 8');await expect(capstone.locator('.go-total')).toContainText('6/6');
  await thesis.getByRole('button',{name:'Chọn học phần',exact:true}).click();
  await expect(thesis.getByRole('checkbox',{name:new RegExp('^'+f.prefix+'C ·')})).toHaveCount(0);
  await expect(thesis.getByRole('checkbox',{name:new RegExp('^'+f.prefix+'OUT ·')})).toHaveCount(0);
  await thesis.getByRole('checkbox',{name:'Hiển thị tất cả học phần',exact:true}).check();
  await thesis.getByLabel('Tìm mã hoặc tên học phần',{exact:true}).fill(f.prefix+'OUT');
  await expect(thesis.getByRole('checkbox',{name:new RegExp('^'+f.prefix+'OUT ·')})).toBeDisabled();
  await optionCheck(thesis,page,f,'TH',false);await optionCheck(thesis,page,f,'C',true);
  await thesis.getByRole('button',{name:'Áp dụng lựa chọn',exact:true}).click();
  await expect(thesis.locator('.go-issues')).toContainText('nhóm bắt buộc');
  await expect(thesis.locator('.go-total')).toContainText('3/6');
  await thesis.getByRole('button',{name:'Chọn học phần',exact:true}).click();
  await optionCheck(thesis,page,f,'C',false);await optionCheck(thesis,page,f,'TH',true);
  await thesis.getByRole('button',{name:'Áp dụng lựa chọn',exact:true}).click();
  await capstone.getByRole('button',{name:'Chọn học phần',exact:true}).click();
  await optionCheck(capstone,page,f,'CP1',false);await optionCheck(capstone,page,f,'CP2',false);
  await optionCheck(capstone,page,f,'CP1',true);await expect(capstone.getByRole('status')).toContainText('Tổng 3 tín chỉ');
  await optionCheck(capstone,page,f,'CP2',true);await expect(capstone.getByRole('status')).toContainText('Tổng 6 tín chỉ');
  await page.screenshot({path:info.outputPath('capstone-picker.png'),fullPage:true});
  await capstone.getByRole('button',{name:'Áp dụng lựa chọn',exact:true}).click();
  await capstone.getByRole('button',{name:'Chọn học phần',exact:true}).click();
  await capstone.getByLabel('Tìm mã hoặc tên học phần',{exact:true}).fill(f.prefix+'GEN');
  await expect(capstone.getByRole('checkbox',{name:new RegExp('^'+f.prefix+'GEN ·')})).toHaveCount(0);
  await capstone.getByRole('checkbox',{name:'Hiển thị tất cả học phần',exact:true}).check();
  await optionCheck(capstone,page,f,'CP1',false);await optionCheck(capstone,page,f,'CP2',false);
  await optionCheck(capstone,page,f,'GEN',true);
  await capstone.getByRole('button',{name:'Áp dụng lựa chọn',exact:true}).click();
  await expect(capstone).toContainText('Học phần nghiên cứu');
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('BB');
  await expect(graduationCard(page,'CAPSTONE')).toContainText('Chuyên đề tốt nghiệp 1');
  await page.getByRole('combobox',{name:'Chuyên ngành',exact:true}).selectOption('AA');
  await expect(graduationCard(page,'CAPSTONE')).toContainText('Học phần nghiên cứu');
  await page.getByRole('button',{name:'Lưu bản nháp',exact:true}).click();
  await expect(page.getByText('Có thay đổi chưa lưu.',{exact:false})).toHaveCount(0);
  const saved=(await api(page,'/api/curriculums/'+draft.id)).data;
  const group=saved.specializationTrees.AA.requirementGroups.find(g=>g.kind==='GRADUATION');
  expect(group.completionOptions.map(o=>o.optionCode)).toEqual(['THESIS','CAPSTONE']);
  expect(group.completionOptions[1].courseCodes).toEqual([f.prefix+'GEN']);
  expect(saved.status).toBe('DRAFT');
  await page.getByRole('button',{name:'Kiểm tra toàn bộ cây',exact:true}).click();
  await expect(page.getByText('Tất cả cây hợp lệ.',{exact:true})).toBeVisible();
  // New Lệnh 8 flow never publishes a curriculum; the active source is a local fixture.
  for(const code of ['C','S','E2','E3','TH']){
    const r=await api(page,'/api/v1/admin/academic-records/attempts','POST',{studentId:f.studentId,curriculumId:f.programId,courseCode:f.prefix+code,
      attempt:{semesterCode:'2026-2',score10:8,completedAt:'2026-06-01T00:00:00Z'}});expect(r.status).toBe(200);
  }
  await nav(page,'Đăng xuất');await login(page,f,'student');
  const first=(await api(page,'/api/v1/student/curriculum/evaluation-official')).data;
  expect(first.recognizedCredits).toBe(17);expect(first.officialGraduationEligible).toBe(true);expect(first.gpaCredits).toBe(17);
  const own=(await api(page,'/api/v1/student/curriculum')).data;
  expect(own.mandatoryCatalogCredits).toBe(6);expect(own.mandatoryPassedCredits).toBe(6);
  const capstoneCourse=own.semesters.flatMap(s=>s.courses).find(c=>c.courseCode===f.prefix+'CP1');
  expect(capstoneCourse.requiredForCompletion).toBe(false);expect(capstoneCourse.graduationGroupComplete).toBe(true);
  await nav(page,'CTĐT của tôi');
  await expect(graduationCard(page,'THESIS')).toContainText('Khóa luận tốt nghiệp');
  await expect(graduationCard(page,'CAPSTONE')).toContainText('Đồ án/Chuyên đề tốt nghiệp');
  await expect(page.getByRole('button',{name:'Chọn học phần',exact:true})).toHaveCount(0);
  await page.screenshot({path:info.outputPath('student-graduation.png'),fullPage:true});
  await nav(page,'Đăng xuất');await login(page,f);
  for(const code of ['CP1','CP2']){
    const r=await api(page,'/api/v1/admin/academic-records/attempts','POST',{studentId:f.studentId,curriculumId:f.programId,courseCode:f.prefix+code,
      attempt:{semesterCode:'2026-2',score10:8,completedAt:'2026-06-02T00:00:00Z'}});expect(r.status).toBe(200);
  }
  await nav(page,'Đăng xuất');await login(page,f,'student');
  const both=(await api(page,'/api/v1/student/curriculum/evaluation-official')).data;
  expect(both.recognizedCredits).toBe(17);expect(both.gpaCredits).toBe(23);expect(both.cumulativeGpa4).toBe(first.cumulativeGpa4);
  await nav(page,'CTĐT của tôi');
});

test('full transcript keeps eight planned periods and three real periods visible without moving retakes',async({page},info)=>{
  const f=fixture(info,'transcript');await login(page,f,'student');await nav(page,'Kết quả học tập');
  const value=(await api(page,'/api/v1/student/academic-records/transcript')).data;
  expect(value.curriculumPlan.semesters).toHaveLength(8);expect(value.attempts).toHaveLength(3);
  expect(value.curriculumPlan.curriculumId).toBe(f.programId);expect(value.curriculumPlan.specializationCode).toBe('AA');
  expect(value.semesters.map(s=>s.semesterCode)).toEqual(['2024-1','2026-2','2026-3']);
  expect(value.summary.cumulativeGpa4).toBe(3.25);expect(value.summary.academicEarnedCredits).toBe(6);
  expect(value.summary.graduationRecognizedCredits).toBe(6);
  await expect(page.locator('.sr-planned-term')).toHaveCount(7);await expect(page.locator('.sr-actual-term')).toHaveCount(3);
  await expect(page.locator('.sr-term-heading')).toHaveCount(10);await expect(page.locator('.sr-period-type')).toHaveCount(0);
  await expect(page.locator('.sr-term-context')).toHaveCount(0);
  await expect(page.locator('.sr-planned-term .sr-term-footer')).toHaveCount(0);
  await expect(page.getByText(/Điểm được đối chiếu từ lần học thực tế|Kỳ dự kiến chưa có ánh xạ|Đầy đủ học phần theo kế hoạch|không cộng tín chỉ kế hoạch/)).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Mở tất cả',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Thu gọn tất cả',exact:true})).toHaveCount(0);
  await expect(page.locator('[data-semester-key="PLAN:8"]')).toHaveCount(0);
  const mixed=page.locator('[data-semester-key="PLAN:1"]'),empty=page.locator('[data-semester-key="PLAN:2"]');
  await expect(mixed).toContainText('5 môn theo CTĐT');await expect(mixed).toContainText('1 môn có kết quả ở kỳ thực tế');
  if(info.project.name==='desktop'){
    await expect(mixed.locator('tbody tr')).toHaveCount(4);
    await expect(mixed.getByRole('row').filter({hasText:f.prefix+'PL1'})).toHaveCount(0);
    for(const code of ['PX1','PX2','PX4','PX5']){
      const cells=mixed.getByRole('row').filter({hasText:f.prefix+code}).getByRole('cell');
      for(const col of [3,4,5,6])await expect(cells.nth(col)).toHaveText('—');
      await expect(cells.nth(7)).toContainText('Nhóm yêu cầu đã hoàn thành');
    }
    await expect(empty.locator('tbody tr')).toHaveCount(2);
    await expect(page.locator('.sr-actual-term').getByRole('row').filter({hasText:f.prefix+'PL8'})).toHaveCount(2);
    await expect(page.locator('[data-semester-key="2026-3"]').getByRole('row').filter({hasText:f.prefix+'PL1'}).getByRole('cell').nth(4)).toHaveText('7,0');
  }else{
    await expect(mixed.locator('.sr-mobile-cards:visible article')).toHaveCount(4);
    await expect(empty.locator('.sr-mobile-cards:visible article')).toHaveCount(2);
    await expect(page.locator('.sr-actual-term .sr-mobile-cards:visible article').filter({hasText:f.prefix+'PL8'})).toHaveCount(2);
  }
  await page.screenshot({path:info.outputPath('full-transcript.png'),fullPage:true});
  await page.getByRole('combobox',{name:'Học kỳ',exact:true}).selectOption('PLAN:4');
  await expect(page.locator('.sr-term')).toHaveCount(1);await expect(page.locator('.sr-term')).toContainText('Nhóm yêu cầu đã hoàn thành');
  await expect(page.locator('.sr-term-metrics')).toContainText('Học thực tế 0 TC');
  await expect(page.locator('.sr-overview article').first()).toContainText('3,25');
  await page.getByPlaceholder('Mã hoặc tên học phần').fill('NOT_FOUND');
  await expect(page.locator('.sr-term')).toHaveCount(1);await expect(page.getByText(/Không có học phần phù hợp bộ lọc/)).toBeVisible();
  await page.getByRole('button',{name:'Xóa bộ lọc',exact:true}).click();
  await page.getByRole('combobox',{name:'Năm học',exact:true}).selectOption('2024');
  await expect(page.locator('.sr-planned-term')).toHaveCount(0);await expect(page.locator('.sr-actual-term')).toHaveCount(1);
  await page.getByRole('combobox',{name:'Chế độ xem',exact:true}).selectOption('highest');
  await expect(page.locator('.sr-actual-term')).toHaveCount(1);await expect(page.getByText(/Không có lần học phù hợp bộ lọc/)).toBeVisible();
  await page.getByRole('button',{name:'Xóa bộ lọc',exact:true}).click();
  await page.getByRole('combobox',{name:'Học kỳ',exact:true}).selectOption('2026-2');
  await page.getByRole('button',{name:'Lịch sử '+f.prefix+'PL8',exact:true}).click();
  await expect(page.getByRole('dialog').getByRole('listitem')).toHaveCount(2);
  await page.getByRole('button',{name:'Đóng lịch sử',exact:true}).click();
  const after=(await api(page,'/api/v1/student/academic-records/transcript')).data;
  expect(after.summary).toEqual(value.summary);expect(after.attempts).toEqual(value.attempts);
});
test('a five-period curriculum without grades has five complete visible empty semester tables',async({page},info)=>{
  const f=fixture(info,'transcript-empty');await login(page,f,'student');await nav(page,'Kết quả học tập');
  await expect(page.locator('.sr-planned-term')).toHaveCount(5);await expect(page.locator('.sr-actual-term')).toHaveCount(0);
  await expect(page.getByText('Chưa có kết quả học tập',{exact:true})).toHaveCount(5);
  await expect(page.getByText('Không đạt',{exact:true})).toHaveCount(0);
  await page.getByRole('combobox',{name:'Học kỳ',exact:true}).selectOption('PLAN:5');
  await expect(page.locator('.sr-term')).toHaveCount(1);await expect(page.locator('.sr-term-metrics')).toContainText('GPA —');
  await expect(page.locator('.sr-term-metrics')).toContainText('Học thực tế 0 TC');
  await expect(page.locator('.sr-term-metrics')).toContainText('Đạt thực tế 0 TC');
  const v=(await api(page,'/api/v1/student/academic-records/transcript')).data;
  expect(v.attempts).toEqual([]);expect(v.semesters).toEqual([]);expect(v.summary.academicEarnedCredits).toBe(0);
  expect(v.summary.cumulativeGpa4).toBeNull();
  await page.screenshot({path:info.outputPath('empty-transcript.png'),fullPage:true});
});

test('a confirmed eight-term calendar merges all tables and keeps actual summer history',async({page},info)=>{
  const f=fixture(info,'transcript');await login(page,f,'student');
  // Official calendar fixture for this isolated program, not a cohort inference.
  await page.route('**/api/v1/student/academic-records/transcript',async route=>{
    const response=await route.fetch(),body=await response.json();
    const calendar=['2023-1','2023-2','2024-1','2024-2','2025-1','2025-2','2026-1','2026-2'];
    for(const s of body.data.curriculumPlan.semesters)s.academicSemesterCode=calendar[s.plannedSemesterNumber-1];
    await route.fulfill({response,json:body});
  });
  await nav(page,'Kết quả học tập');
  await expect(page.locator('.sr-planned-term')).toHaveCount(0);
  await expect(page.locator('.sr-term')).toHaveCount(9); // eight main terms plus actual summer
  await expect(page.getByRole('heading',{name:/Học kỳ dự kiến/})).toHaveCount(0);
  const first=page.locator('[data-semester-key="2023-1"]');
  await expect(first.locator(info.project.name==='desktop'?'tbody tr':'.sr-mobile-cards:visible article')).toHaveCount(5);
  await expect(page.locator('[data-semester-key="2026-3"]')).toContainText('Học kỳ hè');
  await expect(page.locator('.sr-overview article').first()).toContainText('3,25');
  await page.getByRole('combobox',{name:'Học kỳ',exact:true}).selectOption('2023-2');
  await expect(page.locator('.sr-term')).toHaveCount(1);
  await expect(page.locator('.sr-term-metrics')).toContainText('Học thực tế 0 TC');
  await page.getByRole('button',{name:'Xóa bộ lọc',exact:true}).click();
  await page.screenshot({path:info.outputPath('confirmed-calendar.png'),fullPage:true});
});

test('an old transcript API is diagnosed explicitly and reload recovers all five planned subjects',async({page},info)=>{
  const f=fixture(info,'transcript');await login(page,f,'student');
  const endpoint='**/api/v1/student/academic-records/transcript';
  await page.route(endpoint,async route=>{
    const response=await route.fetch(),body=await response.json();
    delete body.data.curriculumPlan;
    await route.fulfill({response,json:body});
  });
  await nav(page,'Kết quả học tập');
  await expect(page.getByRole('alert')).toContainText('cấu trúc cũ');
  await expect(page.locator('.sr-planned-term')).toHaveCount(0);
  await expect(page.locator('.sr-actual-term')).toHaveCount(3);
  await expect(page.locator('.sr-overview article').first()).toContainText('3,25');
  await page.unroute(endpoint);
  // Explicit official calendar fixture; production must never infer this from cohort or grades.
  await page.route(endpoint,async route=>{const response=await route.fetch(),body=await response.json();
    body.data.curriculumPlan.semesters.find(s=>s.semesterKey==='PLAN:1').academicSemesterCode='2026-3';
    await route.fulfill({response,json:body});});
  await page.getByRole('button',{name:'Tải lại bảng điểm',exact:true}).click();
  await expect(page.locator('.sr-planned-term')).toHaveCount(6);
  await expect(page.getByText(/cấu trúc cũ|Chưa có thông tin cây CTĐT/)).toHaveCount(0);
  const planned=page.locator('[data-semester-key="2026-3"]');
  await expect(planned.locator(info.project.name==='desktop'?'tbody tr':'.sr-mobile-cards:visible article')).toHaveCount(5);
  await expect(page.locator('.sr-overview article').first()).toContainText('3,25');
});
