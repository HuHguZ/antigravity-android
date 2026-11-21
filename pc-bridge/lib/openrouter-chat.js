class OpenRouterChatError extends Error {}

function upstreamError(code) {
    const status = Number(code);
    if (status === 429) return new OpenRouterChatError('OpenRouter ограничил число запросов (429). Попробуйте позже.');
    if (status === 402) return new OpenRouterChatError('Недостаточно средств для запроса к OpenRouter (402).');
    const suffix = Number.isInteger(status) && status >= 400 && status <= 599 ? ` (${status})` : '';
    return new OpenRouterChatError(`OpenRouter завершил запрос с ошибкой${suffix}.`);
}

const visibleText = (value) => typeof value === 'string' && value.trim() !== '[REDACTED]' ? value : '';

function visibleReasoning(message) {
    const plain = visibleText(message.reasoning) || visibleText(message.reasoning_content);
    if (plain || !Array.isArray(message.reasoning_details)) return plain;
    return message.reasoning_details.map(detail => {
        if (detail?.type === 'reasoning.text') return visibleText(detail.text);
        if (detail?.type === 'reasoning.summary') return visibleText(detail.summary);
        return '';
    }).join('');
}

function parseCompletion(data) {
    const payload = JSON.parse(data);
    const choice = payload?.choices?.[0];
    if (payload?.error || payload?.type === 'error' || choice?.finish_reason === 'error') {
        throw upstreamError(payload.error?.code ?? payload.code);
    }
    return { choice, usage: payload?.usage };
}

function isCompleteToolCall(call) {
    if (!call.id || !call.function.name || call.type !== 'function') return false;
    try {
        JSON.parse(call.function.arguments || '{}');
        return true;
    } catch (_) {
        return false;
    }
}

async function readOpenRouterCompletion(response, onReasoning) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
        response.destroy();
        throw upstreamError(response.statusCode);
    }

    response.setEncoding('utf8');
    let buffer = '';
    if (!response.headers['content-type']?.includes('text/event-stream')) {
        for await (const chunk of response) buffer += chunk;
        const { choice, usage } = parseCompletion(buffer);
        if (choice?.message) onReasoning(visibleReasoning(choice.message));
        return { message: choice?.message, finishReason: choice?.finish_reason, usage };
    }

    let content = '';
    let usage;
    let finishReason;
    const toolCalls = new Map();
    for await (const chunk of response) {
        buffer += chunk;
        let separator;
        while ((separator = /\r?\n\r?\n/.exec(buffer))) {
            const lines = buffer.slice(0, separator.index).split(/\r?\n/);
            buffer = buffer.slice(separator.index + separator[0].length);
            const data = lines.filter(line => line.startsWith('data:'))
                .map(line => line.slice(5).replace(/^ /, '')).join('\n');
            if (!data.trim()) continue;
            if (data.trim() === '[DONE]') {
                const calls = [...toolCalls.entries()].sort(([a], [b]) => a - b).map(([, call]) => call);
                if (finishReason !== 'length' && calls.length &&
                    (finishReason !== 'tool_calls' && finishReason !== 'stop' || !calls.every(isCompleteToolCall))) {
                    throw new OpenRouterChatError('OpenRouter прислал неполный вызов инструмента.');
                }
                return { message: { content, tool_calls: calls }, finishReason, usage };
            }
            const parsed = parseCompletion(data);
            usage = parsed.usage ?? usage;
            finishReason = parsed.choice?.finish_reason ?? finishReason;
            const delta = parsed.choice?.delta;
            if (!delta) continue;
            if (typeof delta.content === 'string') content += delta.content;
            onReasoning(visibleReasoning(delta));
            if (!Array.isArray(delta.tool_calls)) continue;
            for (const [position, part] of delta.tool_calls.entries()) {
                if (!part || typeof part !== 'object') continue;
                const index = Number.isInteger(part.index) ? part.index : position;
                const call = toolCalls.get(index) || { id: '', type: 'function', function: { name: '', arguments: '' } };
                if (typeof part.id === 'string') call.id += part.id;
                if (typeof part.type === 'string') call.type = part.type;
                if (typeof part.function?.name === 'string') call.function.name += part.function.name;
                if (typeof part.function?.arguments === 'string') call.function.arguments += part.function.arguments;
                toolCalls.set(index, call);
            }
        }
    }
    throw new OpenRouterChatError('Поток ответа OpenRouter прервался до завершения.');
}

module.exports = { OpenRouterChatError, readOpenRouterCompletion };
