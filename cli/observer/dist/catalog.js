import { readdir, readFile, stat } from 'node:fs/promises';
import { homedir, platform } from 'node:os';
import { dirname, join } from 'node:path';
/** How many parent dirs to walk when looking for a composite package root. */
const PACKAGE_ROOT_MAX_DEPTH = 6;
/** Full SemVer, optional prerelease/build. Leading `v` is stripped before matching. */
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;
export const UPD_SOP_CHILDREN = [
    'sop-requirement',
    'sop-design',
    'sop-implement',
    'sop-review',
    'sop-verification',
    'sop-release-check',
    'sop-documentation'
];
const UPD_CHILD_SET = new Set(UPD_SOP_CHILDREN);
const UPD_SLUG = 'using-product-development';
const SUPERPOWERS_SLUG = 'superpowers';
const KNOWN_COMPOSITE_PARENTS = new Set([UPD_SLUG, SUPERPOWERS_SLUG]);
const KNOWN_STANDALONE = new Set(['ui-ux-pro-max']);
export function slugify(value) {
    const slug = value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    return slug || 'skill';
}
export async function listInstalledSkills(extraRoots = []) {
    const roots = [...skillRoots(), ...extraRoots.map(normalizeRoot).filter(Boolean)];
    const found = [];
    const seen = new Set();
    for (const root of roots) {
        for (const path of await findSkillFiles(root, 6)) {
            const parsed = parseSkillFile(path, await readFile(path, 'utf8').catch(() => ''));
            if (!parsed || seen.has(parsed.path))
                continue;
            seen.add(parsed.path);
            found.push(parsed);
        }
    }
    return enrichSkillVersions(await withCompositeParents(found));
}
export function parseSkillFile(path, content) {
    const name = frontmatterName(content) || parentDirName(path);
    if (!name)
        return undefined;
    const slug = slugify(name);
    const parentSlug = primaryParentSlug(slug, path);
    const source = isProjectSkillPath(path) ? 'project' : 'global';
    // Observation identity is SemVer label only — content digest is not used.
    // Single packages declare version in SKILL.md; composite packages inherit from package root later.
    const versionLabel = normalizeVersionLabel(frontmatterField(content, ['version']));
    const base = { slug, name, path, source };
    if (versionLabel)
        base.versionLabel = versionLabel;
    return parentSlug ? { ...base, parentSlug } : base;
}
/** Parse SemVer version from package.json / plugin.json content (UTF-8 BOM tolerated). */
export function versionLabelFromPackageContent(content) {
    try {
        const data = JSON.parse(content.replace(/^﻿/, ''));
        return normalizeVersionLabel(typeof data.version === 'string' ? data.version : undefined);
    }
    catch {
        return undefined;
    }
}
/**
 * Resolve composite package version from the nearest package root.
 * A package root is a dir containing package.json or .codex-plugin/plugin.json.
 * Nearest root wins even when its version is missing/non-SemVer (do not walk further).
 */
export async function resolvePackageVersionFromDir(startDir) {
    let dir = normalizeRoot(startDir);
    for (let i = 0; i <= PACKAGE_ROOT_MAX_DEPTH; i += 1) {
        const packageJson = join(dir, 'package.json');
        const pluginJson = join(dir, '.codex-plugin', 'plugin.json');
        const packageContent = await readFile(packageJson, 'utf8').catch(() => '');
        const pluginContent = await readFile(pluginJson, 'utf8').catch(() => '');
        if (packageContent || pluginContent) {
            return versionLabelFromPackageContent(packageContent)
                ?? (pluginContent ? versionLabelFromPackageContent(pluginContent) : undefined);
        }
        const parent = dirname(dir);
        if (parent === dir)
            break;
        dir = parent;
    }
    return undefined;
}
export async function resolvePackageVersionFromSkillPath(skillPath) {
    return resolvePackageVersionFromDir(dirname(skillPath));
}
/**
 * Fill missing versionLabel from the nearest composite package root
 * (package.json / .codex-plugin/plugin.json). Own SKILL.md version always wins.
 */
export async function enrichSkillVersions(skills) {
    const cache = new Map();
    const result = [];
    for (const skill of skills) {
        if (skill.versionLabel) {
            result.push(skill);
            continue;
        }
        const dir = dirname(skill.path);
        if (!cache.has(dir)) {
            cache.set(dir, await resolvePackageVersionFromDir(dir));
        }
        const packageLabel = cache.get(dir);
        result.push(packageLabel ? { ...skill, versionLabel: packageLabel } : skill);
    }
    return result;
}
export function normalizeVersionLabel(raw) {
    if (!raw)
        return undefined;
    const cleaned = raw.trim().replace(/^['"]|['"]$/g, '').replace(/^[vV]/, '');
    return SEMVER.test(cleaned) ? cleaned : undefined;
}
/** Resolve SemVer version label for a usage against the installed catalog. */
export function resolveSkillVersion(skills, usage) {
    if (!usage)
        return {};
    const skill = (usage.path ? skills.find((item) => item.path === usage.path) : undefined)
        || (usage.slug ? skills.find((item) => item.slug === usage.slug) : undefined);
    return { versionLabel: skill?.versionLabel };
}
export function withCompositeParents(skills) {
    const bySlug = new Map();
    for (const skill of skills) {
        if (!bySlug.has(skill.slug))
            bySlug.set(skill.slug, skill);
    }
    for (const skill of skills) {
        for (const parentSlug of parentSlugsFor(skill.slug, skill.path)) {
            if (bySlug.has(parentSlug))
                continue;
            const parentPath = parentPathFor(skill.path, parentSlug);
            const parent = {
                slug: parentSlug,
                name: parentSlug,
                path: parentPath,
                source: isProjectSkillPath(skill.path) ? 'project' : 'global'
            };
            // Composite parent has no root SKILL.md; inherit package.json version via enrichSkillVersions.
            bySlug.set(parentSlug, parent);
        }
    }
    return [...bySlug.values()];
}
export function parentSlugsFor(slug, path) {
    const key = slugify(slug);
    const parents = [];
    if (UPD_CHILD_SET.has(key) || key.startsWith('sop-')) {
        if (key !== UPD_SLUG)
            parents.push(UPD_SLUG);
    }
    if (key === UPD_SLUG) {
        // leaf only
    }
    if (path && isSuperpowersChildPath(path) && key !== SUPERPOWERS_SLUG) {
        parents.push(SUPERPOWERS_SLUG);
    }
    if (key === 'using-superpowers' && !parents.includes(SUPERPOWERS_SLUG)) {
        parents.push(SUPERPOWERS_SLUG);
    }
    return parents.filter((parent, index) => parents.indexOf(parent) === index && parent !== key);
}
export function primaryParentSlug(slug, path) {
    return parentSlugsFor(slug, path)[0];
}
export function matchSkillUsage(skills, value) {
    const skill = matchSkill(skills, value);
    if (skill) {
        return {
            slug: skill.slug,
            name: skill.name,
            path: skill.path,
            parents: parentSlugsFor(skill.slug, skill.path),
            match: 'path'
        };
    }
    return matchSkillInText(serializeValue(value));
}
export function matchSkillInText(text) {
    if (!text)
        return undefined;
    const fileUsage = matchSkillFilePath(text);
    if (fileUsage)
        return fileUsage;
    const dirUsage = matchSkillDirectoryPath(text);
    if (dirUsage)
        return dirUsage;
    return matchNamedSkillToken(text);
}
export function matchSlashSkillCommands(text) {
    if (!text)
        return [];
    const found = new Map();
    const pattern = /(?:^|[\s`'"(（\[>])([/$])([a-z][a-z0-9-]*)/gi;
    for (const match of text.matchAll(pattern)) {
        const slug = slugify(match[2] || '');
        if (!slug || slug === 'skill')
            continue;
        if (!UPD_CHILD_SET.has(slug) && slug !== UPD_SLUG && slug !== SUPERPOWERS_SLUG && !KNOWN_STANDALONE.has(slug) && !KNOWN_COMPOSITE_PARENTS.has(slug)) {
            // Only hard-attribute known composite/UPD-related slash commands from free text.
            continue;
        }
        const parents = parentSlugsFor(slug);
        const usage = {
            slug,
            name: slug,
            parents,
            match: 'text'
        };
        found.set(slug, usage);
        for (const parent of usage.parents) {
            if (!found.has(parent)) {
                found.set(parent, { slug: parent, name: parent, parents: [], match: 'text' });
            }
        }
    }
    // `/sop-requirement` also implies UPD even if parent loop already added it
    if ([...found.keys()].some((slug) => UPD_CHILD_SET.has(slug)) && !found.has(UPD_SLUG)) {
        found.set(UPD_SLUG, { slug: UPD_SLUG, name: UPD_SLUG, parents: [], match: 'text' });
    }
    return [...found.values()];
}
export function matchSkill(skills, value) {
    const ordered = [...skills].sort((a, b) => b.path.length - a.path.length);
    return ordered.find((skill) => contains(value, skill.path) || contains(value, skill.name) || contains(value, skill.slug));
}
export async function discoverProjectSkillRoots(seedPaths) {
    const roots = new Set();
    for (const seed of seedPaths) {
        if (!seed)
            continue;
        let dir = normalizeRoot(seed);
        for (let i = 0; i < 8; i += 1) {
            const candidate = join(dir, '.agents', 'skills');
            roots.add(candidate);
            const parent = dirname(dir);
            if (parent === dir)
                break;
            dir = parent;
        }
    }
    const envRoots = (process.env.SKILLHUB_PROJECT_SKILL_ROOTS || '')
        .split(/[;]/)
        .map((item) => item.trim())
        .filter(Boolean);
    for (const root of envRoots)
        roots.add(root);
    return [...roots];
}
function matchSkillFilePath(text) {
    const patterns = [
        /(?:^|[\s'"=([{])((?:~|[A-Za-z]:[\\/]|\.\/|\.\.\/|\/)?[^\s'"()]*?\.agents[\\/]skills[\\/]([^\\/"'\s]+)(?:[\\/]skills[\\/]([^\\/"'\s]+))?[\\/][^\s'"()]+)/gi,
        /(?:^|[\s'"=([{])((?:~|[A-Za-z]:[\\/]|\.\/|\.\.\/|\/)?[^\s'"()]*?\.(?:claude|codex|agents)[\\/]skills[\\/]([^\\/"'\s]+)(?:[\\/]skills[\\/]([^\\/"'\s]+))?[\\/][^\s'"()]+)/gi
    ];
    for (const pattern of patterns) {
        pattern.lastIndex = 0;
        const match = pattern.exec(text);
        if (!match)
            continue;
        const fullPath = match[1] || '';
        const first = slugify(match[2] || '');
        const nested = match[3] ? slugify(match[3]) : '';
        if (!first)
            continue;
        // Composite children (.../skills/<child>/...) attribute to the child, then roll up to parent.
        if (nested && (first === SUPERPOWERS_SLUG || first === UPD_SLUG || KNOWN_COMPOSITE_PARENTS.has(first))) {
            return {
                slug: nested,
                name: nested,
                path: fullPath,
                parents: [...new Set([first, ...parentSlugsFor(nested, fullPath)])],
                match: 'file'
            };
        }
        return {
            slug: first,
            name: first,
            path: fullPath,
            parents: parentSlugsFor(first, fullPath),
            match: 'file'
        };
    }
    return undefined;
}
function matchSkillDirectoryPath(text) {
    const pattern = /(?:^|[\s'"=([{])((?:~|[A-Za-z]:[\\/]|\.\/|\.\.\/|\/)?[^\s'"()]*?\.agents[\\/]skills[\\/]([^\\/"'\s]+)(?:[\\/]skills[\\/]([^\\/"'\s]+))?(?=[\\/\s'"()]))/gi;
    pattern.lastIndex = 0;
    const match = pattern.exec(text);
    if (!match)
        return undefined;
    const first = slugify(match[2] || '');
    const nested = match[3] ? slugify(match[3]) : '';
    if (!first)
        return undefined;
    if (nested && (first === SUPERPOWERS_SLUG || first === UPD_SLUG || KNOWN_COMPOSITE_PARENTS.has(first))) {
        return { slug: nested, name: nested, path: match[1], parents: [...new Set([first, ...parentSlugsFor(nested)])], match: 'path' };
    }
    return { slug: first, name: first, path: match[1], parents: parentSlugsFor(first), match: 'path' };
}
function matchNamedSkillToken(text) {
    const lowered = text.toLowerCase();
    const candidates = [
        UPD_SLUG,
        SUPERPOWERS_SLUG,
        'using-superpowers',
        ...UPD_SOP_CHILDREN,
        ...KNOWN_STANDALONE
    ];
    for (const slug of candidates) {
        if (lowered.includes(slug)) {
            return { slug, name: slug, parents: parentSlugsFor(slug), match: 'path' };
        }
    }
    return undefined;
}
function frontmatterName(content) {
    return frontmatterField(content, ['name']);
}
function frontmatterField(content, keys) {
    const match = content.replace(/^\uFEFF/, '').match(/^---\r?\n([\s\S]*?)\r?\n---/);
    if (!match)
        return undefined;
    const block = match[1];
    for (const key of keys) {
        const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const value = block.match(new RegExp(`^${escaped}:\\s*(.+)$`, 'm'))?.[1]?.trim();
        if (value)
            return value.replace(/^['"]|['"]$/g, '');
    }
    return undefined;
}
function parentDirName(path) {
    const parts = path.replace(/\\/g, '/').split('/');
    return parts.length >= 2 ? parts[parts.length - 2] : '';
}
function skillRoots() {
    const home = homedir();
    const roots = [
        join(home, '.claude', 'skills'),
        join(home, '.codex', 'skills'),
        join(home, '.agents', 'skills')
    ];
    if (platform() === 'darwin')
        roots.push(join(home, 'Library', 'Application Support', 'SkillHub', 'skills'));
    else if (platform() === 'win32')
        roots.push(join(process.env.LOCALAPPDATA || join(home, 'AppData', 'Local'), 'SkillHub', 'skills'));
    else
        roots.push(join(process.env.XDG_DATA_HOME || join(home, '.local', 'share'), 'skillhub', 'skills'));
    return roots;
}
async function findSkillFiles(dir, depth) {
    if (depth < 0)
        return [];
    const found = [];
    let entries;
    try {
        entries = await readdir(dir);
    }
    catch {
        return found;
    }
    for (const entry of entries) {
        const full = join(dir, entry);
        if (entry.toLowerCase() === 'skill.md') {
            found.push(full);
            continue;
        }
        try {
            if ((await stat(full)).isDirectory())
                found.push(...await findSkillFiles(full, depth - 1));
        }
        catch {
            // skip
        }
    }
    return found;
}
function contains(value, needle) {
    if (!needle)
        return false;
    if (typeof value === 'string')
        return value.includes(needle) || value.includes(JSON.stringify(needle).slice(1, -1));
    if (Array.isArray(value))
        return value.some((item) => contains(item, needle));
    if (value && typeof value === 'object')
        return Object.values(value).some((item) => contains(item, needle));
    return false;
}
function serializeValue(value) {
    if (typeof value === 'string')
        return value;
    try {
        return JSON.stringify(value ?? '');
    }
    catch {
        return String(value ?? '');
    }
}
function isSuperpowersChildPath(path) {
    const normalized = path.replace(/\\/g, '/').toLowerCase();
    if (normalized.includes('/superpowers/skills/'))
        return true;
    if (/\/\.(?:claude|codex|agents)\/skills\/superpowers\/[^/]+\//.test(normalized))
        return true;
    if (/\/skillhub\/skills\/superpowers\/[^/]+\//.test(normalized))
        return true;
    return false;
}
function isProjectSkillPath(path) {
    return path.replace(/\\/g, '/').toLowerCase().includes('/.agents/skills/');
}
function parentPathFor(childPath, parentSlug) {
    const normalized = childPath.replace(/\\/g, '/');
    const markers = [
        `/${SUPERPOWERS_SLUG}/skills/`,
        `/.agents/skills/${parentSlug}/skills/`,
        `/.claude/skills/${parentSlug}/skills/`,
        `/.codex/skills/${parentSlug}/skills/`,
        `/.agents/skills/${parentSlug}/`,
        `/.claude/skills/${parentSlug}/`,
        `/.codex/skills/${parentSlug}/`
    ];
    for (const marker of markers) {
        const index = normalized.toLowerCase().lastIndexOf(marker.toLowerCase());
        if (index >= 0)
            return normalized.slice(0, index + marker.length - 1);
    }
    const lastSlash = normalized.lastIndexOf('/');
    return lastSlash > 0 ? normalized.slice(0, lastSlash) : normalized;
}
function normalizeRoot(root) {
    return root.trim().replace(/[\\/]+$/, '');
}
