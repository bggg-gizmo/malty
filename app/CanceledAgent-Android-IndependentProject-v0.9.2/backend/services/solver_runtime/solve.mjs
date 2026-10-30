import { parseChallenge } from './solver-engine.mjs';
let input = '';
for await (const data of process.stdin) input += data;
try { process.stdout.write(JSON.stringify(parseChallenge(JSON.parse(input).challenge))+'\n'); }
catch (err) { process.stdout.write(JSON.stringify({unsure:true,reason:String(err)})+'\n'); }
