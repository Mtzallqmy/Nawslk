import http from 'node:http';
const port = Number(process.env.PORT || 3000);
const server = http.createServer((req, res) => {
  res.setHeader('content-type', 'application/json');
  res.setHeader('cache-control', 'no-store');
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200);
    res.end(JSON.stringify({status:'ok'}));
    return;
  }
  res.writeHead(404);
  res.end(JSON.stringify({error:'not_found'}));
});
server.listen(port, '0.0.0.0');
