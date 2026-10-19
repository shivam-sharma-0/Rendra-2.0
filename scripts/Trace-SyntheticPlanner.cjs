// Disposable-fixture diagnostic only. Never records request bodies, credentials or model reasoning.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { once } = require('node:events');

function collectProposal() {
    const decoder = new TextDecoder();
    let pending = '', content = '', finishReason = null, malformedEvents = 0;
    const tools = new Map();
    function event(text) {
        const data = text.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n');
        if (!data || data === '[DONE]') return;
        let parsed;
        try { parsed = JSON.parse(data); } catch { malformedEvents++; return; }
        const choice = parsed.choices?.[0];
        if (!choice) return;
        if (['stop', 'length', 'tool_calls', 'error'].includes(choice.finish_reason)) finishReason = choice.finish_reason;
        const delta = choice.delta || {};
        if (typeof delta.content === 'string') content = (content + delta.content).slice(0, 96000);
        for (const tool of delta.tool_calls || []) {
            const index = Number.isInteger(tool.index) ? tool.index : 0;
            const record = tools.get(index) || { name: 'unknown', arguments: '' };
            if (tool.function?.name === 'proposeContract') record.name = 'proposeContract';
            if (typeof tool.function?.arguments === 'string') record.arguments = (record.arguments + tool.function.arguments).slice(0, 96000);
            tools.set(index, record);
        }
    }
    return {
        push(bytes) {
            pending += decoder.decode(bytes, { stream: true });
            if (pending.length > 2 * 1024 * 1024) throw Error('Synthetic trace event capacity exceeded');
            let boundary;
            while ((boundary = /\r?\n\r?\n/.exec(pending))) {
                event(pending.slice(0, boundary.index));
                pending = pending.slice(boundary.index + boundary[0].length);
            }
        },
        finish() {
            pending += decoder.decode();
            if (pending.trim()) event(pending);
            pending = '';
            return { finishReason, malformedEvents, content, toolCalls: [...tools.values()] };
        }
    };
}

async function main() {
    const directory = path.resolve(process.argv[2] || '');
    const root = path.resolve(__dirname, '../target/real-e2e');
    if (path.dirname(directory) !== root || !/^codex_e2e_[a-f0-9]{12}$/.test(path.basename(directory))
            || !fs.statSync(directory).isDirectory()) throw Error('Owned synthetic evidence directory required');
    const manifest = require('./fixtures/planner-intake.json');
    const routes = new Map([
        ['/api/v1/chat/completions', { provider: 'openrouter', model: manifest.routes.find(route => route.provider === 'openrouter')?.model,
            url: 'https://openrouter.ai/api/v1/chat/completions' }],
        ['/mistral/v1/chat/completions', { provider: 'mistral', model: manifest.routes.find(route => route.provider === 'mistral')?.model,
            url: 'https://api.mistral.ai/v1/chat/completions' }]
    ]);
    let sequence = 0;
    const server = http.createServer(async (request, response) => {
        const abort = new AbortController();
        response.once('close', () => { if (!response.writableEnded) abort.abort(); });
        let trace;
        let provider;
        try {
            const route = routes.get(request.url);
            if (request.method !== 'POST' || !route?.model) {
                response.writeHead(404).end(); return;
            }
            provider = route.provider;
            const chunks = [];
            let bytes = 0;
            for await (const chunk of request) {
                bytes += chunk.length;
                if (bytes > 2 * 1024 * 1024) throw Error('Request capacity exceeded');
                chunks.push(chunk);
            }
            const body = Buffer.concat(chunks);
            const data = JSON.parse(body.toString('utf8'));
            if (data.model !== route.model || data.stream !== true || !request.headers.authorization) {
                response.writeHead(400).end(); return;
            }
            trace = collectProposal();
            const upstream = await fetch(route.url, {
                method: 'POST', redirect: 'error', signal: AbortSignal.any([abort.signal, AbortSignal.timeout(120000)]),
                headers: { 'content-type': 'application/json', authorization: request.headers.authorization }, body
            });
            response.writeHead(upstream.status, { 'content-type': upstream.headers.get('content-type') || 'application/octet-stream' });
            // Error bodies are forwarded unchanged but are never retained in diagnostic artifacts.
            if (!upstream.ok || !upstream.headers.get('content-type')?.includes('text/event-stream')) trace = null;
            for await (const chunk of upstream.body) {
                trace?.push(chunk);
                if (!response.write(chunk)) await once(response, 'drain', { signal: abort.signal });
            }
            response.end();
        } catch {
            if (!response.headersSent) response.writeHead(502);
            response.end();
        } finally {
            if (trace) fs.writeFileSync(path.join(directory, `synthetic-planner-${++sequence}.json`),
                JSON.stringify({ provider, ...trace.finish() }, null, 2), { flag: 'wx' });
        }
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    fs.writeFileSync(path.join(directory, 'planner-trace-ready.json'), JSON.stringify({ port: server.address().port }), { flag: 'wx' });
}

module.exports = { collectProposal };
if (require.main === module) main().catch(() => { console.error('Synthetic planner tracing failed'); process.exitCode = 1; });
