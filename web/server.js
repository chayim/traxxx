import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = process.env.PORT || 3000;
const API_BASE = process.env.API_BASE || 'http://localhost:8080';

const app = express();
app.use(express.json({ limit: '10mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// Proxy /api/* to the Go server so the browser can use a single origin.
app.all('/api/*', async (req, res) => {
    const target = API_BASE + req.originalUrl;
    try {
        const init = {
            method: req.method,
            headers: { 'content-type': 'application/json' },
        };
        if (req.method !== 'GET' && req.method !== 'HEAD') {
            init.body = JSON.stringify(req.body ?? {});
        }
        const r = await fetch(target, init);
        res.status(r.status);
        const text = await r.text();
        res.type(r.headers.get('content-type') || 'application/json').send(text);
    } catch (e) {
        res.status(502).json({ error: 'upstream unavailable', detail: String(e) });
    }
});

app.listen(PORT, () => {
    console.log(`traxxx web on http://localhost:${PORT} -> ${API_BASE}`);
});
