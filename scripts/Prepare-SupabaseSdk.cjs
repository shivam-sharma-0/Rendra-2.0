const fs = require('node:fs');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const config = JSON.parse(fs.readFileSync('/inputs/sdk.json', 'utf8'));
process.chdir('/output');
const packed = JSON.parse(cp.execFileSync('npm', ['pack', `${config.package}@${config.version}`, '--ignore-scripts',
  '--json', '--registry', 'https://registry.npmjs.org', '--cache', '/tmp/npm-cache'], {encoding:'utf8', timeout:60000}))[0];
if (packed.integrity !== config.integrity || !/^[A-Za-z0-9_.-]+\.tgz$/.test(packed.filename)) throw Error('Unproved SDK package');
const archive = fs.readFileSync(packed.filename);
if ('sha512-'+crypto.createHash('sha512').update(archive).digest('base64') !== config.integrity) throw Error('SDK integrity mismatch');
const sdk = cp.execFileSync('tar', ['-xOf', packed.filename, config.browserArtifact], {maxBuffer:4*1024*1024, timeout:10000});
fs.writeFileSync('supabase.js', sdk);
fs.writeFileSync('sdk-receipt.json', JSON.stringify({version:config.version, integrity:config.integrity,
  sha256:crypto.createHash('sha256').update(sdk).digest('hex')}));
fs.unlinkSync(packed.filename);
console.log('Pinned SDK browser fixture prepared');
