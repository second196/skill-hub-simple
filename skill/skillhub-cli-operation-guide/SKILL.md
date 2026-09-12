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
| `--name <name>` | auto-generated | Override the generated name of a composite package. |
| `--description <description>` | auto-generated | Override the generated description of a composite package. |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub backend address. |
| `--json` | off | Print a machine-readable JSON result. |

Examples:

```bash
skillhub upload ./my-skill.zip --category 研发
skillhub upload ./my-skill --category 研发 --service-url http://127.0.0.1:8080
skillhub upload ./my-skill/SKILL.md --category 工具
skillhub upload ./skill-a ./skill-b --category 研发
skillhub upload ./my-skill.zip --category 研发 --json
```

Package requirements:

- If a package has a root-level `SKILL.md`, the complete package is uploaded and nested skills remain ordinary files inside it.
- If a package has no root-level `SKILL.md` but contains one or more nested `SKILL.md` files, treat it as one composite package. The CLI and server generate a root entry file, preserve the complete tree, and must not select or upload one nested skill separately.
- Composite package metadata is generated from package metadata, README content, the input name, or a generic fallback. Use `--name` and `--description` when the caller needs to override it.
- `SKILL.md` must be UTF-8 Markdown with YAML frontmatter.
- Frontmatter requires `name` and `description`.
- `version` is optional; when omitted, the CLI uses `0.0.0`.
- If present, `version` must use Semantic Versioning, for example `1.0.0`, `1.2.3-beta.1`, or `1.0.0+build.5`.
- ZIP files and directories must not include symbolic links, credentials, `.env` files, private keys, or unsafe paths.

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
| `--version <digest>` | latest/default server version | Version digest to download. |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub backend address. |
| `--json` | off | Print a machine-readable JSON result. |

Without `--target`, the CLI installs to SkillHub's user-level storage and exposes the skill through detected Agent user-level skill directories. With `--target`, the final directory is `<target>/<slug>`.

Examples:

```bash
skillhub install using-product-development
skillhub install using-product-development --target .skills
skillhub install using-product-development --target ./vendor/skills
skillhub install using-product-development --version <version-digest>
skillhub install using-product-development --service-url http://127.0.0.1:8080 --json
```

The normal text result contains the slug, final directory, extracted file count, and detected Agent entry points. With `--json`, the result also includes `userInstall` and `agentLinks`.

## Agent operating workflow

When an agent receives a SkillHub task:

1. Use `skillhub list` if the user asks what skills are available.
2. Resolve the requested skill's `slug` from the list output.
3. Use `skillhub install <slug>` to install it in user scope. Use `--target <directory>` only when the user explicitly requests a project or custom directory.
4. Use `skillhub upload <path> --category <category>` only when the user explicitly asks to publish a local skill.
5. Add `--json` when the output will be parsed by another program.
6. Add `--service-url` whenever the backend is not `http://127.0.0.1:8080`.
