---
name: skillhub-cli-operation-guide
description: Use when an AI coding agent needs to operate SkillHub CLI, upload a skill package, list platform skills, install a platform skill, or follow the GitHub-hosted SkillHub CLI guide.
---

# SkillHub CLI

## CLI entry points

The npm package exposes two equivalent executable names:

```bash
skillhub <command> [options]
skillhub-cli <command> [options]
```

Use either name consistently. The default service URL is `http://127.0.0.1:8080`.

Global installation:

```bash
npm install -g @second196/skillhub-cli
```

Without global installation:

```bash
npx @second196/skillhub-cli <command> [options]
```

General help and version:

```bash
skillhub --help
skillhub upload --help
skillhub list --help
skillhub install --help
skillhub --version
```

## Supported commands

### 1. Upload one or more skills

Syntax:

```bash
skillhub upload <input-path...> [options]
```

Each input may be a ZIP archive, a skill directory, or a single `SKILL.md` file. Inputs are uploaded independently; one failure does not stop the remaining inputs.

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `--category <category>` | `其他` | Category stored with the skill. |
| `--name <name>` | from package metadata / README / path | Override composite package name. |
| `--description <description>` | from package metadata / README | Override composite package description. |
| `--skill-version <semver>` | from root SKILL.md or package.json | Explicit SemVer. Required for composite packages that have no root SKILL.md and no package.json version. **Do not use `--version`** — that flag prints the CLI's own version. |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub backend address. |
| `--json` | off | Print a machine-readable JSON result. |

Examples:

```bash
skillhub upload ./my-skill.zip --category 研发
skillhub upload ./my-skill --category 研发 --service-url http://127.0.0.1:8080
skillhub upload ./my-skill/SKILL.md --category 工具
skillhub upload ./composite.zip --category 研发 --skill-version 1.0.0
skillhub upload ./my-skill.zip --category 研发 --json
```

Package requirements (current contract):

- **Single skill (root `SKILL.md` present):** upload the whole directory/ZIP. Nested skills stay as ordinary files.
- **Composite skill (no root `SKILL.md`, but one or more nested `*/SKILL.md`):**
  - CLI/server **do NOT generate a root `SKILL.md`**.
  - If a root `README.md` (any case) already exists, it is kept unchanged.
  - If no root README exists, a `README.md` is created **only inside the uploaded package** (not written back to your working tree) for platform overview display.
  - Platform overview shows root `SKILL.md` when present, otherwise root `README.md`.
  - **Do not invent a root `SKILL.md` for composite packages.**
- **Version is required.** 
  - Single skill: root `SKILL.md` frontmatter `version` (SemVer), or CLI `--skill-version`.
  - Composite: package root `package.json` / `.codex-plugin/plugin.json` `version`, or CLI `--skill-version`.
  - Global `skillhub --version` only prints the **CLI tool version** — it is **not** the skill package version flag.
  - If version is missing, upload fails. Add `package.json` with a SemVer `version` (preferred for composites) or pass `--skill-version 1.0.0`.
- Composite `name`/`description` come from `--name`/`--description` → package.json/plugin.json → README → input path name.
- `SKILL.md` must be UTF-8 Markdown with YAML frontmatter (`name`, `description`, and `version` when it is the metadata source).
- ZIP files and directories must not include symbolic links, credentials, `.env` files, private keys, or unsafe paths.

Composite package.json example (place at package root):

```json
{
  "name": "my-composite-skill",
  "description": "复合Skill包说明",
  "version": "1.0.0"
}
```

Then:

```bash
skillhub upload ./my-composite --category 研发
# or
skillhub upload ./my-composite.zip --category 研发 --skill-version 1.0.0
```

The normal text result contains the uploaded name, slug, and version. With `--json`, the result contains `ok: true` and the server response fields.

### 2. List platform skills

Syntax:

```bash
skillhub list [options]
```

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `--category <category>` | no filter | Only return one category. |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub backend address. |
| `--json` | off | Print the complete list as JSON. |

Examples:

```bash
skillhub list
skillhub list --category 研发
skillhub list --service-url http://127.0.0.1:8080
skillhub list --json
```

The default text output shows each skill's name, slug, category, version, and status. If no skill is found, it prints `暂无技能`.

### 3. Install a platform skill

Syntax:

```bash
skillhub install <slug> [options]
```

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `--target <directory>` | user scope | Explicit project or custom installation parent. |
| `--digest <digest>` | latest/default server version | Platform version digest to download. **Do not use `--version`** (CLI tool version). |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub backend address. |
| `--json` | off | Print a machine-readable JSON result. |

Without `--target`, the CLI installs to SkillHub's user-level storage and exposes the skill through detected Agent user-level skill directories. With `--target`, the final directory is `<target>/<slug>`.

Examples:

```bash
skillhub install using-product-development
skillhub install using-product-development --target .skills
skillhub install using-product-development --target ./vendor/skills
skillhub install using-product-development --digest <version-digest>
skillhub install using-product-development --service-url http://127.0.0.1:8080 --json
```

The normal text output contains the slug, final directory, extracted file count, and detected Agent entry points. With `--json`, the result also includes `userInstall` and `agentLinks`.

## Agent operating workflow

When an agent receives a SkillHub task:

1. Use `skillhub list` if the user asks what skills are available.
2. Resolve the requested skill's `slug` from the list output.
3. Use `skillhub install <slug>` to install it in user scope. Use `--target <directory>` only when the user explicitly requests a project or custom directory.
4. Use `skillhub upload <path> --category <category>` only when the user explicitly asks to publish a local skill.
5. For composite packages **without root `SKILL.md`**:
   - Do **not** create a root `SKILL.md`.
   - Ensure package-root `package.json` has SemVer `version` (preferred), or pass `--skill-version <semver>`.
   - Never use global `--version` for skill package version — it only prints CLI tool version.
   - Optional root `README.md` is used for platform overview; missing README is generated only inside the upload package.
6. Add `--json` when the output will be parsed by another program.
7. Add `--service-url` whenever the backend is not `http://127.0.0.1:8080`.
