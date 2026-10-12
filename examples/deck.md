---

# simpleviz

## an md page, presented

Present, top right, shows this file as slides

---

# Slides are split at ---

- A line of just `---` starts the next slide
- → or space steps forward, ← back, Esc leaves
- The first slide is the title slide

---

>> [the demo](demo.edn)

# >> shows a graph beside the text

A slide whose first line is `>> [the demo](demo.edn)` shows that graph on the right. Being a link, Edit marks it with a », and `simpleviz check` reports it when the file is missing.

- Hover a node for its attributes
- Click one to inspect it, read-only
- Drag to pan, scroll to zoom, − collapses a box

---

>> [states](./states.edn)

# Any graph of the folder

The path is relative to this file, like a link.

---

>> [notes](api/notes.md)

# Another md file

`>> [notes](api/notes.md)` shows it rendered beside the slide.

---

>> ![the demo, exported](overview.png)

# Or an image

An exported PNG or SVG opens as the graph it carries; any other picture shows as it is.
