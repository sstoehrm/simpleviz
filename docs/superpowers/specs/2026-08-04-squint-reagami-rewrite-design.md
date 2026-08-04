# simpleviz frontend rewrite: Squint CLJS + reagami

Date: 2026-08-04
Status: approved

Decisions already made by the user:
- Build model: compile on demand, git-ignore compiled output (node+npm required for frontend dev; `bb serve` runtime unchanged).
- Scope: entire frontend (colors, validate, transform, render, app) becomes Squint CLJS; old .mjs/.js frontend deleted.
- Tests: ported to Squint, still executed by `node --test` on compiled output.

## 1. Toolchain & layout

- `package.json` devDependencies: `squint-cljs`, `reagami`.
- `squint.edn`: `{:paths ["src" "test"] :output-dir "public/js"}` (verify exact key names against squint docs at implementation time).
- Sources: `src/simpleviz/{colors,validate,transform,render,app}.cljs`.
- Tests: `test/simpleviz/{colors,validate,transform,layout}_test.cljs`.
- Delete: `public/app.js`, `public/lib/*.mjs`, `test/*.test.mjs`.
- `.gitignore`: `node_modules/`, `public/js/`.
- bb tasks:
  - `bb build` — npm install if needed, one-shot squint compile, copy reagami ESM from node_modules to `public/js/vendor/`.
  - `bb dev` — build, then squint watch + http server concurrently.
  - `bb test` — build, Clojure server tests, `node --test` over compiled `*_test.mjs` (explicit file list/glob: squint output uses underscores, which node's default test-file patterns do not match).
  - `bb serve` — unchanged.

## 2. Module loading

- `index.html`: import map `{"imports": {"reagami": "/js/vendor/<reagami-entry>.mjs"}}`; entry `<script type="module" src="/js/simpleviz/app.mjs">`. Reagami entry filename verified from `node_modules/reagami/package.json` at implementation time.
- ELK stays a vendored classic script (global `ELK`).
- Squint code: `(:require ["reagami" :refer [render]])`.
- Node tests never import reagami (UI not unit-tested), so no resolver tricks under node.

## 3. Data shapes (idiomatic squint)

- `validate` returns `{:nodes {"a" {:id .. :name .. :type .. :attrs ..}} :edges [..] :boxes [..] :boxes-by-name {..} :parent-of {..} :warnings [..]}` — plain JS objects/arrays at runtime; server JSON flows in without conversion.
- All existing behavior preserved: validation rules + warnings, never-throw guarantee, string coercion of name/type, golden-angle color tables + FNV-1a + probing (deterministic, sorted assignment), ELK transform (ids `n:`/`b:`, layered/RIGHT/INCLUDE_CHILDREN, node sizing via injected measure), container-relative edge-coordinate offsetting.

## 4. UI with reagami

- One state atom `{:graph :layout :error :warnings :selected :collapsed}`; `add-watch` → `(render app-el (app-view @state))`.
- All DOM building becomes hiccup: svg, defs/marker, boxes/edges/nodes layers, banners (warning banner collapsible), details panel with ✕ close.
- Pan/zoom hybrid: wheel/pointer listeners attached once to a static wrapper div in index.html (outside reagami's tree); they mutate a plain `view` object and imperatively set the viewport `<g>` transform; hiccup also renders the transform from `view` so re-renders stay consistent. Fit-to-window on first render only; view survives live reload.
- Selection state-driven: `:selected` payload → highlight class + details panel content (kind, title, (type), all attrs).
- Live reload: 1 s `/api/version` polling, error banner keeps last good render, reload failures reset the mtime so they retry (same semantics as today).

## 5. Tests

- Ported 1:1 (36 tests incl. ELK layout integration and edge-container regression) using node's runner via interop: `(:require ["node:test" :refer [test]] ["node:assert/strict" :as assert])`; assertions on the new shapes.

## 6. Unchanged

- `server/serve.clj`, spec'd behavior, `style.css` (minor selector cleanup), `examples/demo.edn`.
- README: Development section rewritten (node+npm required for frontend dev, `bb dev` workflow).
