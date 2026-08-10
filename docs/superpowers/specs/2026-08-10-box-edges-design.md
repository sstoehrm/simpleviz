# Edges to and between boxes

**Date:** 2026-08-10
**Status:** Approved

## Purpose

Allow edge endpoints to name boxes, not only nodes: `[:web :backend]`
(node→box) and `[:backend :storage]` (box→box), so architecture files can
express connections to whole zones.

## Endpoint resolution (server, `graph.clj`)

- An endpoint may be a node id or a box name. When an identifier names
  BOTH, the node wins — same rule as box components — and a warning is
  emitted (`"\"x\" names both a node and a box; edge [..] gets the node"`).
- Unknown endpoints keep today's behavior: warning + edge skipped. The
  warning text becomes `unknown node or box`.
- Normalized edges gain `:source-id`/`:target-id`: prefixed ids
  (`"n:web"`, `"b:backend"`). `:source`/`:target` remain display names.
  (The frontend's `to-elk` already prefers `:source-id`, added for the
  collapse path.)
- Ordering: `build-edges` needs box names, so it runs against nodes AND
  the deduplicated box-name set; the containment pass below runs after
  membership/cycle resolution.

## Containment rule

An edge is skipped with a warning when one endpoint is a box that
transitively contains the other endpoint (via resolved `:parent-of`).
This includes box self-loops `[:backend :backend]`. Node self-loops stay
allowed. Implemented as a post-pass in `normalize` after `break-cycles`
(needs the final parent relation); the warning names the edge and the
containment (`"edge [backend api]: backend contains api, skipped"`).

## Frontend

- `to-elk`: no structural change (uses `:source-id`). ELK must route
  edges whose endpoints are EXPANDED compound boxes under
  `INCLUDE_CHILDREN`; the collapsed(leaf) case is proven by the collapse
  path. A real-ELK layout test verifies the expanded case FIRST — if ELK
  needs extra layout options, they are added in that task.
- `prune.cljs`:
  - `resolve-end` handles box endpoints: an endpoint box swallowed by a
    collapsed ancestor re-attaches to the shell (owner mapping extends to
    dead boxes); an endpoint that IS the collapsed box stays.
  - Wholly-interior detection (edge drop + `contents-changed`) and
    `collapse-scene`'s instant filter check box endpoints against dead
    boxes, not only nodes against dead nodes.
- Scene/canvas/hit/inspector: no changes expected (edges are geometry by
  the time they reach them; payload shows display names).

## Compare mode

No structural change: edges match by unordered display-name pair; union
already contains removed boxes, so removed edges to removed boxes ghost
like node edges. `diff-stops`/cycling unaffected.

## Docs & examples

- README data-format section: endpoints may be nodes or boxes; box
  self-/containment edges are skipped with a warning.
- Plugin skill (`plugins/simpleviz/skills/simpleviz/SKILL.md`): replace
  the "never box ids" rule with the new rules (node-wins ambiguity,
  containment skip). Re-verify the changed skill wording per
  writing-skills (retrieval probe on the new rules).
- `examples/demo.edn` + `examples/demo-next.edn`: add a box-endpoint edge
  (e.g. `[:web :backend] {:direction :-> :name "ingress"}`) so the demo
  and compare demo exercise it.

## Testing

- `graph_test.clj`: box endpoint resolution (`:source-id "b:.."`),
  node-wins ambiguity + warning, unknown endpoint wording, containment
  skip (direct member, transitive member, box self-loop), node self-loop
  unchanged.
- `diff_test.clj`: matched/added/removed box-endpoint edges across files.
- JS `layout_test`: real ELK layout of an edge to an expanded box and a
  box→box edge (front-loaded).
- JS `prune_test`: box endpoint re-attachment, wholly-interior box-edge
  drop + `:diff-inside`, `collapse-scene` filter.
- Manual: demo pair renders the ingress edge; collapse `backend` and the
  box edge re-attaches sensibly; compare mode shows it.

## Out of scope

- Lifting the containment restriction (revisit on demand).
- Ports/anchoring preferences on box borders (ELK decides).
