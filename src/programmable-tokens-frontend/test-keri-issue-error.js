const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');

const source = fs.readFileSync('lib/utils/keri-issue-error.ts', 'utf8');
const javascript = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const compiledExports = {};
vm.runInNewContext(javascript, { exports: compiledExports, Error, JSON });

assert.equal(compiledExports.keriIssueErrorMessage(new Error(JSON.stringify({ error: 'Check Veridian profile' }))),
  'Check Veridian profile');
assert.match(compiledExports.keriIssueErrorMessage(new Error('Request timeout')),
  /may have completed; check Veridian/);
assert.equal(compiledExports.keriIssueErrorMessage(new Error('Plain error')), 'Plain error');

console.log('KERI issue error messages passed');
