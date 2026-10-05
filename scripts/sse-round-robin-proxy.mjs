import http from 'node:http';

const port = Number(process.env.AGENT_PROXY_PORT || 8790);
const upstreams = (
    process.env.AGENT_PROXY_UPSTREAMS || 'http://127.0.0.1:8787,http://127.0.0.1:8788'
)
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean)
    .map((value) => new URL(value));

if (!Number.isInteger(port) || port <= 0 || upstreams.length < 2) {
    throw new Error('AGENT_PROXY_PORT and at least two AGENT_PROXY_UPSTREAMS are required');
}

let nextUpstream = 0;
const server = http.createServer((request, response) => {
    if (request.url === '/__proxy/status') {
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ upstreams: upstreams.map(String), nextUpstream }));
        return;
    }

    const selected = upstreams[nextUpstream % upstreams.length];
    nextUpstream += 1;
    const headers = { ...request.headers, host: selected.host };
    delete headers['content-length'];
    const upstreamRequest = http.request(
        {
            protocol: selected.protocol,
            hostname: selected.hostname,
            port: selected.port,
            method: request.method,
            path: request.url,
            headers,
        },
        (upstreamResponse) => {
            const responseHeaders = { ...upstreamResponse.headers };
            responseHeaders['x-horizen-upstream'] = selected.origin;
            response.writeHead(upstreamResponse.statusCode || 502, responseHeaders);
            response.flushHeaders();
            upstreamResponse.pipe(response);
            response.on('close', () => upstreamResponse.destroy());
        }
    );
    upstreamRequest.setNoDelay(true);
    upstreamRequest.on('error', (error) => {
        if (!response.headersSent) {
            response.writeHead(502, { 'content-type': 'application/json' });
        }
        response.end(JSON.stringify({ error: error.message }));
    });
    request.on('aborted', () => upstreamRequest.destroy());
    request.pipe(upstreamRequest);
});

server.keepAliveTimeout = 65_000;
server.headersTimeout = 70_000;
server.listen(port, '127.0.0.1', () => {
    process.stdout.write(`round-robin proxy listening on 127.0.0.1:${port}\n`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => server.close(() => process.exit(0)));
}
