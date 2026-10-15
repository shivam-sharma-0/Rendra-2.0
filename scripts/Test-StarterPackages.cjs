// Runs only on a fresh runner-owned /checks mount in the pinned disposable container.
const fs = require('node:fs');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const base = '/checks';
const offline = process.argv.includes('--offline');
if (!offline) {
  fs.cpSync('/inputs/vite', base + '/vite', { recursive: true });
  fs.cpSync('/inputs/server', base + '/server', { recursive: true });
  fs.copyFileSync('/inputs/owned.json', base + '/owned.json');
}
if (!fs.existsSync(base + '/owned.json')) throw new Error('Missing disposable ownership marker');
const owner = JSON.parse(fs.readFileSync(base + '/owned.json'));
if (!/^codepal-codex-starter-it-[a-f0-9]{12}$/.test(owner.project)) throw new Error('Invalid owner');
const report = { project: owner.project, node: process.version, npm: cp.execFileSync('npm', ['--version'], { encoding: 'utf8' }).trim(), offline, checks: [] };
function run(dir, args, kind) {
  const start = Date.now();
  const result = cp.spawnSync('npm', args, { cwd: base + '/' + dir, encoding: 'utf8', timeout: 120000,
    env: { ...process.env, HOME: '/tmp', npm_config_cache: base + '/cache', npm_config_update_notifier: 'false' } });
  report.checks.push({ package: dir, kind, milliseconds: Date.now() - start, exit: result.status, error: result.error?.code });
  fs.writeFileSync(base + '/packages-result.json', JSON.stringify(report, null, 2));
  if (result.status !== 0) throw new Error(kind + ': ' + dir + '\n' + result.stdout + result.stderr);
}
for (const dir of ['vite', 'server']) {
  if (!offline && owner.generateLocks) run(dir, ['install', '--package-lock-only', '--ignore-scripts', '--no-audit', '--no-fund'], 'generate-lock');
  const lock = base + '/' + dir + '/package-lock.json';
  const before = crypto.createHash('sha256').update(fs.readFileSync(lock)).digest('hex');
  run(dir, ['ci', ...(offline ? ['--offline'] : []), '--prefer-offline', '--ignore-scripts', '--no-audit', '--no-fund'], offline ? 'warm-cache-clean-ci' : 'cold-modules-ci');
  run(dir, ['run', 'build'], 'typecheck-build');
  if (before !== crypto.createHash('sha256').update(fs.readFileSync(lock)).digest('hex')) throw new Error('Install changed lockfile');
}
if (offline) {
  fs.cpSync(base + '/vite', base + '/changed-package', { recursive: true, filter: p => !p.split('/').includes('node_modules') });
  const original = JSON.parse(fs.readFileSync(base + '/changed-package/package.json'));
  const pinned = JSON.parse(fs.readFileSync(base + '/changed-package/package-lock.json')).packages['node_modules/scheduler'].version;
  original.dependencies.scheduler = pinned;
  fs.writeFileSync(base + '/changed-package/package.json', JSON.stringify(original));
  run('changed-package', ['install', '--offline', '--ignore-scripts', '--no-audit', '--no-fund'], 'refresh-added-dependency');
  const refreshed = JSON.parse(fs.readFileSync(base + '/changed-package/package-lock.json'));
  if (refreshed.packages[''].dependencies.scheduler !== pinned) throw new Error('Changed dependency was not resolved into lock');
  run('changed-package', ['ci', '--offline', '--ignore-scripts', '--no-audit', '--no-fund'], 'ci-after-refresh');
  run('changed-package', ['run', 'build'], 'build-after-refresh');
}
report.complete = true;
fs.writeFileSync(base + '/packages-result.json', JSON.stringify(report, null, 2));
console.log(JSON.stringify(report));
