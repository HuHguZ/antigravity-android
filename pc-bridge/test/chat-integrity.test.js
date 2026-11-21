const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { setImmediate } = require('node:timers/promises');
const source = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');

test('new turns do not inherit the previous reply in the same conversation', () => {
    const start = source.indexOf('function recordActiveTurnSnapshot(');
    const end = source.indexOf('async function executeAgyTurnStreaming(', start);
    const byId = new Map();
    const byConv = new Map();
    const context = { activeTurnsById: byId, activeTurnsByConv: byConv, Date };
    const record = vm.runInNewContext(`(${source.slice(start, end).trim()})`, context);
    record('old', 'chat', { status: 'completed', reply: 'previous answer' });
    record('new', 'chat', { status: 'running' });
    assert.notEqual(byId.get('new').reply, 'previous answer');
    assert.equal(byId.get('old').reply, 'previous answer');
});

function loadOpenRouterTurn(overrides = {}) {
    const start = source.indexOf('async function executeOpenRouterStreamingTurn(');
    const end = source.indexOf('function loadAiPermissionsConfig(', start);
    const context = {
        require, Date, AbortController, INSTANCE_NAME: 'test', turnCancellations: new Map(),
        liveOpenRouterModelsMemoryCache: [],
        loadOpenRouterConfig: () => ({ apiKey: '' }),
        classifyOpenRouterModel: () => ({}), recordActiveTurnSnapshot: () => {},
        executeAgyTurnStreaming: () => { throw new Error('Unexpected provider switch'); },
        loadAgentSystemPrompt: () => '', extractPhoneActionsFromText: () => [],
        buildOpenRouterToolSet: async () => ({ tools: [], nameToRef: new Map() }),
        formatToolSummary: (name) => name,
        extensionRuntime: require('../lib/extension-runtime'),
        ...require('../lib/openrouter-chat'),
        ...overrides
    };
    const extractStart = source.indexOf('function extractThinkingAndCleanReply(');
    const extractEnd = source.indexOf('function extractPhoneActionsFromText(', extractStart);
    context.extractThinkingAndCleanReply = vm.runInNewContext(`(${source.slice(extractStart, extractEnd).trim()})`, context);
    return vm.runInNewContext(`(${source.slice(start, source.lastIndexOf('\n}', end) + 2).trim()})`, context);
}

function mockOpenRouter(responses, overrides = {}) {
    const calls = [];
    const execute = loadOpenRouterTurn({
        loadOpenRouterConfig: () => ({ apiKey: 'mock-key-no-live-access' }),
        https: { request(url, options, onResponse) {
            const request = new EventEmitter();
            const { status = 200, type = 'text/event-stream', send } = responses[calls.length];
            const response = Object.assign(new PassThrough(), { statusCode: status, headers: { 'content-type': type } });
            request.destroy = error => { response.destroy(); if (error) request.emit('error', error); };
            options.signal?.addEventListener('abort', () => request.destroy(new Error('aborted')), { once: true });
            let body = '';
            request.write = data => { body += data; };
            request.end = (data = '') => {
                calls.push(JSON.parse(body + data));
                onResponse(response);
                send(response);
            };
            return request;
        } },
        ...overrides
    });
    return { calls, execute };
}

const frame = (value) => `data: ${typeof value === 'string' ? value : JSON.stringify(value)}\r\n\r\n`;
const deltaFrame = (delta, finish_reason = null) => frame({ choices: [{ index: 0, delta, finish_reason }] });
const prompt = { turnId: 'openrouter-test', model: 'test/model', cleanUserPrompt: 'hello', allowTranscriptWrite: false };

test('OpenRouter without credentials reports failure and never substitutes Gemini', async () => {
    const events = [];
    const execute = loadOpenRouterTurn();
    const result = await execute({ model: 'anthropic/test-model', cleanUserPrompt: 'hello' }, e => events.push(e));
    assert.equal(result.ok, false);
    assert.equal(result.model, 'anthropic/test-model');
    assert.equal(events.at(-1).type, 'done');
});

test('OpenRouter publishes UTF-8 reasoning before completion, then joins tool and reply deltas', async () => {
    const events = [];
    const snapshots = [];
    const toolCalls = [];
    let upstream;
    const { execute, calls } = mockOpenRouter([
        { send(response) {
            upstream = response;
            const chunk = Buffer.from(`: OPENROUTER PROCESSING\r\n\r\n${deltaFrame({
                reasoning: 'Думаю',
                reasoning_details: [
                    { type: 'reasoning.text', text: 'Думаю', signature: 'hidden-signature' },
                    { type: 'reasoning.encrypted', data: 'hidden-content' }
                ]
            })}`);
            const split = chunk.indexOf(Buffer.from('Д')) + 1;
            response.write(chunk.subarray(0, split));
            response.write(chunk.subarray(split));
        } },
        { send(response) {
            response.end(deltaFrame({ reasoning_content: 'Уточняю' }) + deltaFrame({ content: 'От' }) +
                deltaFrame({ content: 'вет' }, 'stop') + frame({ choices: [], usage: { completion_tokens: 8 } }) + frame('[DONE]'));
        } }
    ], {
        recordActiveTurnSnapshot: (_, __, patch) => snapshots.push(patch),
        buildOpenRouterToolSet: async () => ({ tools: [{ type: 'function', function: { name: 'mock_read' } }], nameToRef: new Map([['mock_read', 'mock:read']]) }),
        extensionRuntime: {
            ...require('../lib/extension-runtime'),
            executeBrokerToolCall: (ref, args) => { toolCalls.push([ref, args]); return { ok: true, output: 'fixture' }; }
        }
    });
    const pending = execute(prompt, event => events.push(event));
    await setImmediate();
    try {
        assert.equal(calls[0].stream, true);
        assert.equal(events.at(-1).type, 'thinking_delta');
        assert.equal(events.at(-1).turnId, prompt.turnId);
        assert.equal(events.at(-1).delta, 'Думаю');
        assert.equal(snapshots.at(-1).thinking, 'Думаю');
        assert.equal(upstream.writableEnded, false);
    } finally {
        upstream.end(deltaFrame({ reasoning_details: [{ type: 'reasoning.summary', summary: ' дальше' }] }) +
            deltaFrame({ tool_calls: [{ index: 0, id: 'call_a', type: 'function', function: { name: 'mock_', arguments: '{"key":' } }] }) +
            deltaFrame({ tool_calls: [{ index: 0, function: { name: 'read', arguments: '"value"}' } }] }, 'tool_calls') + frame('[DONE]'));
    }
    const result = await pending;
    assert.equal(result.ok, true);
    assert.equal(result.reply, 'Ответ');
    assert.equal(result.thinking, 'Думаю дальше\n\nУточняю');
    assert.equal(events.filter(event => event.type === 'thinking_delta').map(event => event.delta).join(''), result.thinking);
    assert.equal(result.outputTokensCount, 8);
    assert.deepEqual(toolCalls, [['mock:read', { key: 'value' }]]);
    assert.equal(calls[1].messages.find(message => message.role === 'tool').tool_call_id, 'call_a');
    assert.doesNotMatch(JSON.stringify(events), /hidden-/);
});

test('OpenRouter reports rate/length limits and preserves reasoning on a failed or interrupted stream', async () => {
    const thinkingFrame = deltaFrame({ reasoning: 'Видимое объяснение' });
    const incompleteTool = { index: 0, id: 'mock-call', type: 'function', function: { name: 'mock-read', arguments: '{' } };
    const cases = [
        [{ status: 429, type: 'application/json', send: response => response.end('{"error":{"message":"hidden-429"}}') }, '', /429/],
        [{ send: response => response.end(thinkingFrame + deltaFrame({}, 'length') + frame('[DONE]')) }, 'Видимое объяснение', /лимит.*токен/i],
        [{ send: response => response.end(thinkingFrame + deltaFrame({ content: 'Начало', tool_calls: [incompleteTool] }, 'length') + frame('[DONE]')) }, 'Видимое объяснение', /лимит.*токен/i, 'Начало'],
        [{ send: response => response.end(thinkingFrame + deltaFrame({ tool_calls: [incompleteTool] }, 'tool_calls') + frame('[DONE]')) }, 'Видимое объяснение', /неполный вызов/],
        [{ send: response => response.end(thinkingFrame + frame({ error: { code: 502, message: 'hidden-502' } })) }, 'Видимое объяснение', /502/],
        [{ send: response => response.end(thinkingFrame) }, 'Видимое объяснение', /прервал/i]
    ];
    for (const [mock, thinking, error, partialReply] of cases) {
        const events = [];
        const { execute, calls } = mockOpenRouter([mock]);
        const result = await execute(prompt, event => events.push(event));
        assert.equal(calls.length, 1);
        assert.equal(result.ok, false);
        assert.equal(result.thinking, thinking);
        assert.match(result.error, error);
        assert.equal(result.reply, partialReply || result.error);
        assert.doesNotMatch(JSON.stringify(events), /hidden-/);
    }
});

test('OpenRouter cancellation keeps received reasoning and closes the active request', async () => {
    const turnCancellations = new Map();
    let upstream;
    const { execute } = mockOpenRouter([{ send(response) {
        upstream = response;
        response.write(deltaFrame({ reasoning: 'Получено' }));
    } }], { turnCancellations });
    const pending = execute(prompt, event => {
        if (event.type === 'thinking_delta') turnCancellations.get(prompt.turnId)();
    });
    await setImmediate();
    if (!upstream.destroyed) upstream.end();
    const result = await pending;
    assert.equal(result.ok, false);
    assert.match(result.error, /Остановлено/);
    assert.equal(result.thinking, 'Получено');
    assert.equal(upstream.destroyed, true);
    assert.equal(turnCancellations.size, 0);
});

test('OpenRouter accepts a JSON completion when the requested stream is unavailable', async () => {
    const { execute, calls } = mockOpenRouter([{ type: 'application/json', send: response => response.end(JSON.stringify({
        choices: [{ message: { content: 'Ответ', reasoning_details: [{ type: 'reasoning.summary', summary: 'Вывод' }] }, finish_reason: 'stop' }]
    })) }]);
    const result = await execute(prompt);
    assert.equal(calls.length, 1);
    assert.equal(result.ok, true);
    assert.equal(result.reply, 'Ответ');
    assert.equal(result.thinking, 'Вывод');
});

test('plan and verify prompts reach the actual agent instead of fabricated release notes', async () => {
    const start = source.indexOf('async function executeAgyTurnStreaming(');
    const end = source.indexOf('// Управление плагинами', start);
    const execute = vm.runInNewContext(`(${source.slice(start, source.lastIndexOf('\n}', end) + 2).trim()})`, {
        require, Date, DEFAULT_WORKSPACE: 'test', CHAT_FAST_WORKSPACE: 'test',
        inFlightTurns: new Map(), cachedAgyModels: [{ id: 'test' }], resetConversationsSet: new Set(),
        isExplicitPcWorkspaceTask: () => false,
        loadAgentSystemPrompt: () => { throw new Error('REAL_AGENT_PIPELINE'); },
        fs: { writeFileSync: () => { throw new Error('Unexpected filesystem write'); } }
    });
    for (const cleanUserPrompt of ['/plan change', '/verify change', 'проверь структуру проекта']) {
        await assert.rejects(execute({ model: 'test', cleanUserPrompt }), /REAL_AGENT_PIPELINE/);
    }
});
