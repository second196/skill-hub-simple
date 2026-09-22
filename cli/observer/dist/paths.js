import { homedir, platform } from 'node:os';
import { join } from 'node:path';
export function observabilityDir() {
    if (process.env.SKILLHUB_OBSERVABILITY_DIR)
        return process.env.SKILLHUB_OBSERVABILITY_DIR;
    if (platform() === 'darwin')
        return join(homedir(), 'Library', 'Application Support', 'SkillHub', 'observability');
    if (platform() === 'win32') {
        const base = process.env.LOCALAPPDATA || join(homedir(), 'AppData', 'Local');
        return join(base, 'SkillHub', 'observability');
    }
    const base = process.env.XDG_DATA_HOME || join(homedir(), '.local', 'share');
    return join(base, 'skillhub', 'observability');
}
export function eventsPath() {
    return join(observabilityDir(), 'events.jsonl');
}
export function clientPath() {
    return join(observabilityDir(), 'client.json');
}
export function spoolDir() {
    return join(observabilityDir(), 'spool');
}
export function scanStatePath() {
    return join(observabilityDir(), 'last-scan.json');
}
export function selfCommand(args) {
    const quoted = [quote(observerNodePath()), quote(observerScriptPath()), ...args.map(quote)];
    return quoted.join(' ');
}
export function configPath() {
    return join(observabilityDir(), 'config.json');
}
export function statePath() {
    return join(observabilityDir(), 'state.json');
}
export function sessionsDir() {
    return join(observabilityDir(), 'sessions');
}
export function sessionFilePath(clientName, safeId) {
    return join(sessionsDir(), clientName, `${safeId}.jsonl`);
}
export function spoolTmpDir() {
    return join(spoolDir(), '.tmp');
}
export function lockDir() {
    return join(spoolDir(), '.locks');
}
export function lockFilePath(name) {
    return join(lockDir(), `${name}.lock`);
}
export function spoolJobPath(safeId) {
    return join(spoolDir(), `${safeId}.json`);
}
export function logsDir() {
    return join(observabilityDir(), 'logs');
}
export function logPath() {
    return join(logsDir(), 'observer.log');
}
export function binDir() {
    return join(observabilityDir(), 'bin');
}
export function drainCmdPath() {
    return join(binDir(), 'drain.cmd');
}
export function hookCmdPath() {
    return join(binDir(), platform() === 'win32' ? 'hook.cmd' : 'hook.sh');
}
export function drainVbsPath() {
    return join(binDir(), 'drain-hidden.vbs');
}
export function observerNodePath() {
    return process.execPath;
}
export function observerScriptPath() {
    return String(process.argv[1] || '');
}
export function selfArgv(args) {
    return [observerScriptPath(), ...args];
}
function quote(value) {
    if (!/[ \t"$&'();<>\\|`]/.test(value))
        return value;
    return `"${value.replace(/(["\\])/g, '\\$1')}"`;
}
