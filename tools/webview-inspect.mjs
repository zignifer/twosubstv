// Local developer-only diagnostics via an explicitly forwarded Android WebView devtools port.
// Never evaluate cookies, storage, pairing codes or authentication URLs with this tool.
import {readFile} from 'node:fs/promises';
const expression = process.argv[2] === '--file' ? await readFile(process.argv[3], 'utf8') : process.argv[2];
if (!expression) throw new Error('Supply a narrow diagnostic expression.');
const targets = await (await fetch('http://127.0.0.1:9223/json')).json();
const target = targets.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
if (!target) throw new Error('No debuggable app WebView.');
const socket = new WebSocket(target.webSocketDebuggerUrl);
const timer = setTimeout(() => { console.error('WebView inspection timed out'); process.exit(1); }, 15000);
socket.addEventListener('open', () => socket.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression,returnByValue:true,awaitPromise:true}})));
socket.addEventListener('message', e => {
  const response=JSON.parse(e.data);
  if(response.id!==1)return;
  clearTimeout(timer);
  if(response.result?.exceptionDetails || response.error) {
    console.error(JSON.stringify(response.result?.exceptionDetails || response.error));
    socket.close(); process.exitCode=1; return;
  }
  console.log(JSON.stringify(response.result?.result?.value ?? response.result?.result?.description,null,2));
  socket.close();
});
