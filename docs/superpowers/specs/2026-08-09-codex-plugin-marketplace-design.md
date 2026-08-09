# Codex plugin marketplace

**Date:** 2026-08-09
**Status:** Approved

## Purpose

Make the repository installable as a Codex plugin marketplace, analogous to
its existing Claude Code marketplace. The plugin gives Codex the same
simpleviz EDN authoring and CLI knowledge without maintaining a second copy of
the skill.

## Shared skill

`plugins/simpleviz/skills/simpleviz/SKILL.md` remains the single source of
truth for both Claude Code and Codex. Its existing Agent Skills frontmatter
and instructions are already cross-runtime compatible.

Add `plugins/simpleviz/skills/simpleviz/agents/openai.yaml` with Codex-facing
display name, short description, and a default prompt. Generate this metadata
from the skill-creator tooling rather than duplicating procedural
instructions in the YAML file.

The existing `.claude-plugin` files remain unchanged.

## Codex plugin manifest

Add `plugins/simpleviz/.codex-plugin/plugin.json` as a skills-only plugin. It
contains:

- the stable plugin name `simpleviz`;
- version `0.3.0`, matching the current simpleviz release;
- author, repository, homepage, and concise description metadata;
- `"skills": "./skills/"`;
- interface metadata for the Simpleviz display name, descriptions, developer,
  Productivity category, write capability, website, and starter prompts.

The manifest declares no MCP server, app, hooks, authentication, or assets.

## Repo marketplace

Add `.agents/plugins/marketplace.json` with marketplace name and display name
`simpleviz`. Its sole entry points to `./plugins/simpleviz` and includes:

- `policy.installation: "AVAILABLE"`;
- `policy.authentication: "ON_INSTALL"`;
- `category: "Productivity"`.

The relative source path follows the Codex repo-marketplace convention. No
personal marketplace or user configuration is modified by the repository
change.

## Installation documentation

Add a `Codex plugin` section to `README.md` next to the Claude Code section:

    codex plugin marketplace add sstoehrm/simpleviz
    codex plugin add simpleviz@simpleviz

The first command is supported by the local CLI's Git marketplace source
syntax (`owner/repo`); the second selects the plugin and marketplace by their
shared stable name.

## Validation and testing

- Run the skill validator on `plugins/simpleviz/skills/simpleviz`.
- Run the Codex plugin validator on `plugins/simpleviz`.
- Parse the marketplace and both plugin manifests as JSON.
- Confirm the marketplace name, plugin name, manifest name, and plugin folder
  all equal `simpleviz`, and confirm the source path is
  `./plugins/simpleviz`.
- Exercise representative simpleviz authoring prompts in fresh agent contexts
  without and with the packaged shared skill. Record the no-skill control and
  verify that the packaged skill produces canonical map-form nodes, edges, and
  boxes. A successful no-skill control is acceptable: because this change
  packages an unchanged shared skill, the check establishes preserved
  canonical behavior rather than a causal skill-driven improvement.
- Review the final diff to ensure no existing Claude plugin file changed.

## Error handling

Schema or metadata errors fail validation before handoff. Skill-behavior
failures are corrected in the shared skill and re-tested, so both runtimes
receive the same fix. Installation failures are reported by Codex's existing
marketplace and plugin commands; this repository adds no installer logic.

## Out of scope

- Publishing to OpenAI's universal public plugin directory.
- Adding an MCP server or plugin UI.
- Creating a second Codex-only copy of the simpleviz skill.
- Installing the marketplace into the current user's Codex configuration.
