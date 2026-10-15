const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

// Runs a saved synthetic artifact in cached, network-isolated Chromium. No provider/browser network.
module.exports = function browserCheck(projectRoot, actions, owner, index) {
  if (!/^codex_e2e_[a-f0-9]{12}$/.test(owner) || !Number.isInteger(index) || actions.length > 24) {
    throw Error('Invalid benchmark browser ownership/recipe');
  }
  let site = projectRoot;
  const pointer = path.join(projectRoot, '.codepal/site/CURRENT');
  if (fs.existsSync(pointer)) {
    const id = fs.readFileSync(pointer, 'utf8').trim();
    if (!/^[a-f0-9-]{36}$/.test(id)) throw Error('Invalid saved-site pointer');
    site = path.join(projectRoot, '.codepal/site', id);
  } else if (fs.existsSync(path.join(projectRoot, 'package.json'))) {
    return { passed: false, reason: 'Built frontend has no saved site artifact', actions: [] };
  }
  if (!fs.existsSync(path.join(site, 'index.html'))) return { passed: false, reason: 'Missing saved entrypoint', actions: [] };
  if (fs.lstatSync(site).isSymbolicLink()) throw Error('Benchmark site cannot be a symlink');
  const command = args => execFileSync('wsl.exe', ['-e', ...args], {
    encoding: 'utf8', timeout: 60000, maxBuffer: 1048576,
  }).trim();
  const linuxSite = command(['wslpath', '-u', site]);
  const probe = command(['wslpath', '-u', path.join(__dirname, '../src/main/resources/sandbox/browser/probe.mjs')]);
  const image = command(['docker', 'image', 'inspect', 'codepal-browser:efeb16a78e4c', '--format', '{{.Id}}']);
  if (!/^sha256:[a-f0-9]{64}$/.test(image)) throw Error('Cached browser image unavailable');
  const raw = command(['docker', 'run', '--rm', '--name', owner + '-browser-' + index,
    '--label', 'com.codepal.real-e2e=' + owner, '--pull', 'never', '--network', 'none', '--user', '1000:1000',
    '--cpus', '1', '--memory', '512m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    '--mount', 'type=bind,source=' + linuxSite + ',target=/site,readonly',
    '--mount', 'type=bind,source=' + probe + ',target=/opt/codepal/probe.mjs,readonly',
    '--entrypoint', 'node', image, '/opt/codepal/probe.mjs', '/site', '300',
    Buffer.from(JSON.stringify({ schema: 1, actions })).toString('base64')]);
  const report = JSON.parse(raw.split('\n').filter(line => line.startsWith('{')).at(-1));
  return { passed: report.loaded && !report.blank && report.issues.length === 0
    && report.acceptance.length === actions.length && report.acceptance.every(action => action.passed), report };
};
