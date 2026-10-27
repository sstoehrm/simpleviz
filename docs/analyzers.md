# Analyzers (experimental)

An analyzer block in an md file turns a folder of code into a graph. The
page shows the block as a link; following it opens the graph beside the
text, made from the code as it is on disk, and made again when the code
changes.

    ```simpleviz-analyzer
    >> ../src/
    "**.clj" #(->> % clj-module public-namespace-graph)
    ```

- `>>` names the folder, relative to the md file, as a path or a link
  (`>> [the code](../src/)`). It must be inside the served folder.
- Then pairs of a pattern and a fn. A pattern is a glob over the paths
  in the folder, or a vector of them: `"**.clj"` matches every `.clj`
  file at any depth, `"*.clj"` those directly in it,
  `["**.clj" "**.cljc"]` both kinds. Dot files, dot folders,
  `node_modules` and symlinks are never read.
- The fn gets the files the pattern matched, `[{:path "app/core.clj"
  :text "…"}]`, and returns a graph, as an EDN file holds one. The
  graphs of several pairs are merged.
- The fns run in a sandbox, in a process of their own, for 30 seconds at
  most: they read no files themselves, they get the files' text.

The graph is read-only: it is made, not written.

## Clojure

Two tools for Clojure, `.clj`, `.cljc`, `.cljs` and `.bb` files:

- `(clj-module files)` — each file as `{:ns "app.core" :file
  "app/core.clj" :defs [{:name "run" :kind "defn" :private false :line 7
  :calls ["app.util/shout" …]}] :methods [{:multi "app.ui/render" :line
  12 :calls […]}]}`, or `{:file :error}` when it doesn't read. Calls are
  the symbols a def names, resolved through the ns form's aliases,
  `:refer` and `:use :only`, and the namespace's own defs. A local that
  shadows a def still counts as a call to it; what `:refer :all` brings
  is not resolved.
- `(public-namespace-graph modules)` — a box per namespace, a node per
  public fn, macro and multimethod in it, and an arrow where one calls
  another, directly or through private fns. A multimethod calls what
  its methods call, in whichever namespace they are.

Both are plain data, so a block can change what it shows before the
graph is made:

    ```simpleviz-analyzer
    >> ../src/
    "**.cljs" (fn [files]
                (->> (clj-module files)
                     (remove #(clojure.string/ends-with? (str (:ns %)) "-test"))
                     public-namespace-graph))
    ```

## More

- [Documents](documents.md) — md files and the graphs beside them.
- [Diagrams](diagrams.md) — the graph format a fn returns.
