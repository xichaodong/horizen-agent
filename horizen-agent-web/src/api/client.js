/** 统一处理 JSON、文件上传和流式响应的 HTTP 请求入口。 */
export function request(url, options = {}) {
    const headers = new Headers(options.headers);
    let body = options.body;
    if (
        body &&
        typeof body === 'object' &&
        !(typeof FormData !== 'undefined' && body instanceof FormData)
    ) {
        body = JSON.stringify(body);
        headers.set('Content-Type', 'application/json');
    } else if (typeof body === 'string' && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
    }
    return fetch(url, {...options, headers, body});
}

/** 提交 JSON 或流式 POST 请求，统一携带取消信号和响应格式。 */
export function post(url, body, {signal, stream = false} = {}) {
    return request(url, {
        method: 'POST',
        body,
        signal,
        headers: stream ? {Accept: 'text/event-stream'} : undefined,
    });
}
