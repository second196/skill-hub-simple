import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isOurObserverHook, rewriteEventHooks } from './install.js';
const LAUNCHER = String.raw `C:\Users\Administrator\AppData\Local\SkillHub\observability\bin\hook.cmd`;
describe('isOurObserverHook', () => {
    it('recognizes historical double-escaped skill-hub-simple paths', () => {
        assert.equal(isOurObserverHook(String.raw `"D:\\nodejs\\node.exe" "D:\\program\\skill-hub-simple\\observer\\dist\\index.js" hook --phase pre --provider codex`), true);
        assert.equal(isOurObserverHook(String.raw `"D:\\nodejs\\node.exe" "D:\\program\\skill-hub-simple\\cli\\observer\\dist\\index.js" hook --phase stop --provider claude-code`), true);
    });
    it('recognizes npm package name and stable launcher', () => {
        assert.equal(isOurObserverHook('skillhub-observer hook --phase pre --provider codex'), true);
        assert.equal(isOurObserverHook(`${LAUNCHER} pre claude-code`), true);
        assert.equal(isOurObserverHook(`"${LAUNCHER}" pre codex`), true);
        assert.equal(isOurObserverHook(String.raw `C:/Users/Administrator/AppData/Local/SkillHub/observability/bin/hook.sh pre codex`), true);
    });
    it('recognizes launcher written with doubled backslashes', () => {
        const doubled = String.raw `"C:\\Users\\Administrator\\AppData\\Local\\SkillHub\\observability\\bin\\hook.cmd" pre codex`;
        assert.equal(isOurObserverHook(doubled), true);
        const single = String.raw `C:\Users\Administrator\AppData\Local\SkillHub\observability\bin\hook.cmd pre codex`;
        assert.equal(isOurObserverHook(single), true);
    });
    it('never claims third-party hooks', () => {
        assert.equal(isOurObserverHook(String.raw `"D:\nodejs\node.exe" "C:\Users\Administrator\.skillhub\collectors\codex\hook-handler.cjs"`), false);
        assert.equal(isOurObserverHook('cc-skill-trace hook-capture --provider codex'), false);
        assert.equal(isOurObserverHook('cc-skill-trace hook-capture --post --provider codex'), false);
    });
    it('ignores non-hook commands even if they mention skillhub-observer', () => {
        assert.equal(isOurObserverHook('skillhub-observer install'), false);
        assert.equal(isOurObserverHook('node skillhub-observer.js'), false);
        assert.equal(isOurObserverHook('skillhub-observer config-set --host 1.2.3.4 --port 8080'), false);
    });
});
describe('rewriteEventHooks', () => {
    it('drops every owned hook and keeps third-party, then writes exactly one', () => {
        const oldA = String.raw `"D:\\nodejs\\node.exe" "D:\\program\\skill-hub-simple\\observer\\dist\\index.js" hook --phase pre --provider codex`;
        const oldB = String.raw `"D:\\nodejs\\node.exe" "D:\\program\\skill-hub-simple\\cli\\observer\\dist\\index.js" hook --phase pre --provider codex`;
        const third = String.raw `"D:\nodejs\node.exe" "C:\Users\Administrator\.skillhub\collectors\codex\hook-handler.cjs"`;
        const entries = [
            { hooks: [{ type: 'command', command: third, commandWindows: third }] },
            { matcher: '*', hooks: [{ type: 'command', command: oldA }] },
            { matcher: '*', hooks: [{ type: 'command', command: oldB }] },
            { matcher: '*', hooks: [{ type: 'command', command: oldA }] }
        ];
        const next = rewriteEventHooks(entries, [{
                command: `${LAUNCHER} pre codex`,
                matcher: '*'
            }]);
        const commands = next.flatMap((e) => (e.hooks || []).map((h) => h.command || ''));
        assert.equal(commands.filter((c) => c.includes('skill-hub-simple')).length, 0);
        assert.equal(commands.filter((c) => c.includes('hook-handler')).length, 1);
        assert.equal(commands.filter((c) => c.includes('hook.cmd')).length, 1);
        assert.equal(next.length, 2);
    });
    it('is idempotent for repeated installs', () => {
        const cmd = `${LAUNCHER} pre codex`;
        let entries = rewriteEventHooks([], [{ command: cmd, matcher: '*' }]);
        entries = rewriteEventHooks(entries, [{ command: cmd, matcher: '*' }]);
        entries = rewriteEventHooks(entries, [{ command: cmd, matcher: '*' }]);
        assert.equal(entries.length, 1);
        assert.equal(entries[0].hooks?.length, 1);
        assert.equal(entries[0].hooks?.[0].command, cmd);
    });
    it('writes one entry per matcher for Claude PreToolUse', () => {
        const pre = `${LAUNCHER} pre claude-code`;
        const next = rewriteEventHooks([], [
            { command: pre, matcher: 'Skill' },
            { command: pre, matcher: 'Read|ReadFile' }
        ]);
        assert.deepEqual(next.map((e) => e.matcher), ['Skill', 'Read|ReadFile']);
        assert.equal(next.every((e) => e.hooks?.length === 1), true);
    });
    it('matches commandWindows ownership too', () => {
        const ownedWindows = 'cmd /d /s /c "skillhub-observer hook --phase pre --provider codex"';
        const next = rewriteEventHooks([{ hooks: [{ type: 'command', command: 'other', commandWindows: ownedWindows }] }], [{ command: `${LAUNCHER} pre codex`, matcher: '*' }]);
        assert.equal(next.length, 1);
        assert.equal(next[0].hooks?.[0].command, `${LAUNCHER} pre codex`);
    });
    it('clears empty shells after removing owned hooks', () => {
        const old = String.raw `"D:\\program\\skill-hub-simple\\cli\\observer\\dist\\index.js" hook --phase pre --provider codex`;
        const next = rewriteEventHooks([{ matcher: '*', hooks: [{ type: 'command', command: old }] }], [{ command: `${LAUNCHER} pre codex`, matcher: '*' }]);
        assert.equal(next.length, 1);
        assert.equal(next[0].matcher, '*');
    });
});
