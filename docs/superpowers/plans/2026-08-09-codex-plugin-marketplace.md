# Codex Plugin Marketplace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make simpleviz installable from this repository as a Codex skills-only plugin while sharing the existing Claude-compatible skill.

**Architecture:** Keep `plugins/simpleviz/skills/simpleviz/SKILL.md` as the sole workflow definition. Add Codex presentation metadata, a Codex plugin manifest beside the Claude manifest, and a repository marketplace catalog that points at the same plugin directory.

**Tech Stack:** Agent Skills Markdown/YAML, Codex plugin and marketplace JSON, Python validation scripts, Codex CLI.

## Global Constraints

- Keep `plugins/simpleviz/skills/simpleviz/SKILL.md` as the single source of truth; do not create a Codex-only copy.
- Do not modify `.claude-plugin/marketplace.json` or `plugins/simpleviz/.claude-plugin/plugin.json`.
- Use plugin and marketplace name `simpleviz` and plugin version `0.3.0`.
- Use marketplace policies `AVAILABLE` and `ON_INSTALL`, category `Productivity`, and source path `./plugins/simpleviz`.
- Do not add an MCP server, app, hooks, authentication implementation, plugin UI, or personal Codex configuration.
- Preserve unrelated untracked `.claude/`, `.clj-kondo/`, and `.lsp/` content.

---

### Task 1: Codex plugin package

**Files:**
- Create: `.agents/plugins/marketplace.json`
- Create: `plugins/simpleviz/.codex-plugin/plugin.json`
- Create: `plugins/simpleviz/skills/simpleviz/agents/openai.yaml`
- Verify unchanged: `.claude-plugin/marketplace.json`
- Verify unchanged: `plugins/simpleviz/.claude-plugin/plugin.json`

**Interfaces:**
- Consumes: the existing skill directory at `plugins/simpleviz/skills/simpleviz/`
- Produces: a `simpleviz` marketplace entry resolving to `./plugins/simpleviz`, a `simpleviz` plugin manifest loading `./skills/`, and Codex skill-picker metadata

- [x] **Step 1: Record the no-skill baseline**

Run the following request in a fresh agent context without supplying the simpleviz skill:

```text
Create a simpleviz EDN graph with web and api nodes, a directed HTTP edge from web to api, and a backend box containing api. Return only EDN.
```

Record whether the response uses all three canonical map forms: `:nodes` keyed by node id, `:edges` keyed by `[from to]`, and `:boxes` with `:components`. This is the no-guidance control required before validating the shared skill; do not edit the skill based on assumed failures. A successful control is acceptable and does not establish that the unchanged shared skill caused an improvement.

Completed 2026-08-09: the no-skill control used all three canonical map forms.

- [x] **Step 2: Run the package preflight and verify it fails**

Run:

```bash
python3 -c 'from pathlib import Path; required=[Path(".agents/plugins/marketplace.json"),Path("plugins/simpleviz/.codex-plugin/plugin.json"),Path("plugins/simpleviz/skills/simpleviz/agents/openai.yaml")]; missing=[str(path) for path in required if not path.is_file()]; assert not missing, f"missing Codex package files: {missing}"'
```

Expected: FAIL with `missing Codex package files` listing all three paths.

- [x] **Step 3: Scaffold the repo-local plugin and marketplace**

Run:

```bash
python3 /home/soeren/.codex/skills/.system/plugin-creator/scripts/create_basic_plugin.py simpleviz \
  --path /home/soeren/repos/private/simpleviz/plugins \
  --marketplace-path /home/soeren/repos/private/simpleviz/.agents/plugins/marketplace.json \
  --marketplace-name simpleviz \
  --with-marketplace
```

Expected: creates only `.agents/plugins/marketplace.json` and `plugins/simpleviz/.codex-plugin/plugin.json` inside the existing plugin tree.

- [x] **Step 4: Replace the generated plugin manifest with project metadata**

Set `plugins/simpleviz/.codex-plugin/plugin.json` to:

```json
{
  "name": "simpleviz",
  "version": "0.3.0",
  "description": "Create, serve, and compare EDN-driven system diagrams with simpleviz",
  "author": {
    "name": "Sören Stöhrmann",
    "url": "https://github.com/sstoehrm"
  },
  "homepage": "https://github.com/sstoehrm/simpleviz",
  "repository": "https://github.com/sstoehrm/simpleviz",
  "skills": "./skills/",
  "interface": {
    "displayName": "Simpleviz",
    "shortDescription": "Create and serve EDN system diagrams.",
    "longDescription": "Author canonical simpleviz graph EDN, serve live diagrams, and compare architecture versions.",
    "developerName": "Sören Stöhrmann",
    "category": "Productivity",
    "capabilities": ["Write"],
    "websiteURL": "https://github.com/sstoehrm/simpleviz",
    "defaultPrompt": [
      "Create a simpleviz diagram for this system.",
      "Update this simpleviz EDN graph.",
      "Compare these two simpleviz architectures."
    ]
  }
}
```

- [x] **Step 5: Generate Codex skill-picker metadata**

Read `/home/soeren/.codex/skills/.system/skill-creator/references/openai_yaml.md`, then run:

```bash
python3 /home/soeren/.codex/skills/.system/skill-creator/scripts/generate_openai_yaml.py \
  plugins/simpleviz/skills/simpleviz \
  --interface 'display_name=Simpleviz' \
  --interface 'short_description=Create and serve EDN system diagrams' \
  --interface 'default_prompt=Use $simpleviz to create or update an EDN system diagram and explain how to view it.'
```

Expected: `agents/openai.yaml` contains only generated interface metadata and does not duplicate the graph-format instructions.

- [x] **Step 6: Run the shared-skill behavior check**

Run the Step 1 request in a fresh agent context with the skill explicitly supplied from `plugins/simpleviz/skills/simpleviz`. Expected EDN shape:

```edn
{:nodes {:web {} :api {}}
 :edges {[:web :api] {:direction :-> :type "http"}}
 :boxes {:backend {:components #{:api}}}}
```

Equivalent names and extra free-form attributes are acceptable. The response must retain the canonical map forms and must not invent `:from`, `:to`, `:groups`, or `:children` fields. This validates the packaged, unchanged shared skill's canonical behavior; it is not a before/after causality test when the no-skill control also succeeds.

- [x] **Step 7: Validate the package and marketplace**

Run:

```bash
python3 /home/soeren/.codex/skills/.system/skill-creator/scripts/quick_validate.py plugins/simpleviz/skills/simpleviz
python3 /home/soeren/.codex/skills/.system/plugin-creator/scripts/validate_plugin.py plugins/simpleviz
python3 -c 'import json; from pathlib import Path; market=json.loads(Path(".agents/plugins/marketplace.json").read_text()); manifest=json.loads(Path("plugins/simpleviz/.codex-plugin/plugin.json").read_text()); claude_manifest=json.loads(Path("plugins/simpleviz/.claude-plugin/plugin.json").read_text()); entry=market["plugins"][0]; assert market["name"]==entry["name"]==manifest["name"]==claude_manifest["name"]=="simpleviz"; assert entry["source"]=={"source":"local","path":"./plugins/simpleviz"}; assert entry["policy"]=={"installation":"AVAILABLE","authentication":"ON_INSTALL"}; assert entry["category"]=="Productivity"; assert manifest["skills"]=="./skills/"'
git diff --exit-code -- .claude-plugin/marketplace.json plugins/simpleviz/.claude-plugin/plugin.json
```

Expected: both validators report success, the Python assertion exits zero, and the Claude-file diff is empty.

- [x] **Step 8: Commit the Codex package**

```bash
git add .agents/plugins/marketplace.json plugins/simpleviz/.codex-plugin/plugin.json plugins/simpleviz/skills/simpleviz/agents/openai.yaml
git commit -m "feat: add Codex plugin marketplace"
```

---

### Task 2: Installation documentation and final verification

**Files:**
- Modify: `README.md`
- Verify: `.agents/plugins/marketplace.json`
- Verify: `plugins/simpleviz/.codex-plugin/plugin.json`
- Verify: `plugins/simpleviz/skills/simpleviz/agents/openai.yaml`

**Interfaces:**
- Consumes: marketplace name `simpleviz` and plugin name `simpleviz` from Task 1
- Produces: copyable Git marketplace and plugin installation commands for repository users

- [x] **Step 1: Run the documentation preflight and verify it fails**

Run:

```bash
rg -F 'codex plugin marketplace add sstoehrm/simpleviz' README.md
```

Expected: exit status 1 because the Codex installation command is absent.

- [x] **Step 2: Add the Codex plugin section**

Append immediately after the existing Claude Code plugin section:

```markdown
## Codex plugin

The same skill is available to Codex through this repository's plugin
marketplace:

    codex plugin marketplace add sstoehrm/simpleviz
    codex plugin add simpleviz@simpleviz
```

- [x] **Step 3: Verify the documentation and package**

Run:

```bash
rg -F 'codex plugin marketplace add sstoehrm/simpleviz' README.md
rg -F 'codex plugin add simpleviz@simpleviz' README.md
python3 /home/soeren/.codex/skills/.system/skill-creator/scripts/quick_validate.py plugins/simpleviz/skills/simpleviz
python3 /home/soeren/.codex/skills/.system/plugin-creator/scripts/validate_plugin.py plugins/simpleviz
git diff --check
```

Expected: both README searches match, both validators report success, and `git diff --check` prints nothing.

- [x] **Step 4: Review the complete change set**

Run:

```bash
git status --short
git diff -- README.md .agents/plugins/marketplace.json plugins/simpleviz/.codex-plugin/plugin.json plugins/simpleviz/skills/simpleviz/agents/openai.yaml
git diff --exit-code -- .claude-plugin/marketplace.json plugins/simpleviz/.claude-plugin/plugin.json
```

Expected: only the plan, README, and three Codex package files are new or modified; unrelated untracked directories remain untouched; the Claude-file diff is empty.

- [x] **Step 5: Commit the documentation**

```bash
git add README.md docs/superpowers/plans/2026-08-09-codex-plugin-marketplace.md
git commit -m "docs: document Codex plugin installation"
```
