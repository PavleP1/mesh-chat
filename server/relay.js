const net = require('net'); const clients = new Set();
net.createServer(s => { clients.add(s); s.on('data', d => { for (const c of clients) if (c !== s) c.write(d); });
  s.on('close', () => clients.delete(s)); s.on('error', () => clients.delete(s)); }).listen(9000, () => console.log('relay on :9000'));
