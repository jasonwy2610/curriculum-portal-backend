import {defineConfig} from '@playwright/test';
// Backend and credentials are injected only by the isolated Java test harness.
const backend = new URL(process.env.VITE_API_BASE_URL || 'http://invalid');
const port=Number(process.env.PORTAL_E2E_WEB_PORT);
if (!Number.isInteger(port) || port<1024 || port>65535) throw new Error('An isolated frontend port is required');
if (backend.hostname !== '127.0.0.1' || !process.env.PORTAL_E2E_FIXTURES)
  throw new Error('Run browser acceptance through LocalMongoHttpIntegrationTest; local backend required.');
export default defineConfig({
  testDir: './e2e', workers: 1, fullyParallel: false, retries: 0, timeout: 120000,
  reporter: [['list'], ['json', {outputFile: '../target/lenh7-browser-results.json'}]],
  outputDir: '../target/browser-artifacts',
  use: {baseURL: `http://localhost:${port}`, browserName: 'chromium', timezoneId: 'Asia/Ho_Chi_Minh',
    actionTimeout:15000, navigationTimeout:20000, trace: 'off', video: 'off', screenshot: 'only-on-failure'},
  projects: [{name:'desktop',use:{viewport:{width:1920,height:1080}}},
    {name:'mobile',use:{viewport:{width:390,height:844},isMobile:true,hasTouch:true}}],
  webServer: {command:`npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`,url:`http://localhost:${port}`,
    reuseExistingServer:false,timeout:30000},
});
