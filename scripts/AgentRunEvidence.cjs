const fs = require('node:fs');
const path = require('node:path');
const policy = require('./fixtures/agent-benchmark-policy.json');

// Only the sanitized, owned synthetic fixtures call this helper. No credentials are persisted.
async function retainRunEvidence(api, projectId, run, evidence) {
  if (!/^codex_e2e_[a-f0-9]{12}$/.test(path.basename(evidence))
      || !/^[a-f0-9-]{36}$/.test(projectId) || !/^[a-f0-9-]{36}$/.test(run?.id)) {
    throw Error('Owned synthetic run evidence required');
  }
  const save = (name, data) => fs.writeFileSync(path.join(evidence, name + '.json'), JSON.stringify(data, null, 2));
  const prefix = `/api/projects/${projectId}/agent-runs/${run.id}`;
  const steps = await api(prefix + '/steps');
  save('run-' + run.id, {projectId, run, steps});
  if (run.status !== 'FAILED') return steps;
  try {
    const deadline = Date.now() + policy.failedDraftWaitMs;
    let info = await api(prefix + '/failed-draft');
    while (info?.state === 'UNAVAILABLE' && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, policy.failedDraftPollMs));
      info = await api(prefix + '/failed-draft');
    }
    const bodies = [];
    let bytes = 0;
    for (const file of (info.files || []).slice(0, 100)) {
      if (bytes >= 2000000) break;
      const body = await api(prefix + '/failed-draft/file?path=' + encodeURIComponent(file.path));
      bytes += Buffer.byteLength(JSON.stringify(body));
      if (bytes <= 2000000) bodies.push(body);
    }
    save('failed-draft-' + run.id, {projectId, runId:run.id, info, bodies});
  } catch {
    save('failed-draft-' + run.id, {projectId, runId:run.id, unavailable:true});
  }
  return steps;
}

module.exports = { retainRunEvidence };
