const fs = require('node:fs');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const offline = process.argv.includes('--offline');
const root = '/checks/site';
fs.mkdirSync(root, {recursive:true});
for (const name of ['package.json', 'package-lock.json', 'starter-static.json', 'design-system.css']) {
  fs.copyFileSync('/inputs/' + name, root + '/' + name);
}
fs.copyFileSync('/inputs/fixture.html', root + '/index.html');
const digest = name => crypto.createHash('sha256').update(fs.readFileSync(root + '/' + name)).digest('hex');
const lock = digest('package-lock.json');
const source = digest('index.html');
const environment = {...process.env, HOME:'/tmp', npm_config_cache:'/checks/cache', npm_config_update_notifier:'false'};
const installed = cp.spawnSync('npm', ['ci', ...(offline ? ['--offline'] : []), '--ignore-scripts', '--no-audit', '--no-fund'],
  {cwd:root, env:environment, encoding:'utf8', timeout:120000});
if (installed.status !== 0) throw Error('Pinned install failed: ' + installed.stderr);
if (lock !== digest('package-lock.json')) throw Error('Install changed the lockfile');
const compiler = fs.readFileSync('/inputs/compiler.mjs', 'utf8');
const build = () => cp.spawnSync(process.execPath, ['--input-type=module', '--eval', compiler],
  {cwd:root, env:environment, encoding:'utf8', timeout:30000});
let result = build();
if (result.status !== 0) throw Error(result.stdout + result.stderr);
const css = fs.readFileSync(root + '/dist/rendra.css', 'utf8');
const html = fs.readFileSync(root + '/dist/index.html', 'utf8');
if (!css.includes('.text-4xl') || !css.includes('.app-card') || !css.includes('--app-accent')) throw Error('Portable utility/foundation CSS missing');
if (!html.includes('href="rendra.css"') || html.includes('cdn.')) throw Error('Static output must use its local stylesheet');
if (source !== digest('index.html')) throw Error('Build changed source');
fs.writeFileSync(root + '/index.html', '<script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"></script>');
result = build();
if (result.status === 0) throw Error('Runtime styling CDN was accepted');
fs.copyFileSync('/inputs/fixture.html', root + '/index.html');
result = build();
if (result.status !== 0) throw Error('Fixture restore failed');
const report = {offline, installed:true, utilities:true, foundation:true, immutableSource:true, rejectsRuntimeCdn:true, restored:true};
fs.writeFileSync('/checks/result.json', JSON.stringify(report));
console.log(JSON.stringify(report));
