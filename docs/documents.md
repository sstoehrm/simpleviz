# Documents

An `.md` file opens rendered, the way GitHub shows it, in a centered
column:

    simpleviz notes.md

Edit (top right) switches to the plain text, Read back. Format tidies
the markdown, and the **vi** button turns on vi keys — modes, motions,
operators, text objects, `.`, `:w`. The browser checks the spelling of
the prose, not of code, links or formulas. Edit the file anywhere else
and the page follows.

## Links

Ordinary markdown links to `.edn`, `.png`, `.svg` and `.md` files
follow, so the same file reads right on GitHub:

    The flow is in [the payment graph](pay/flow.edn).
    See [auth](auth.md) and [the API change](api.edn?compare=next).

- A graph opens to the right of the text, live and editable; an md file
  opens in place of this one, and the trail at the top leads back.
- `?compare=next` shows the graph compared with its fork.
- In the plain text, a » beside the line follows its link.
- A link to a graph that isn't there yet offers to create it, empty or
  as a copy of another graph.
- Links are relative to the md file and never reach above the served
  folder. `simpleviz check notes.md` lists the broken ones, without a
  server.

## Docs on graph elements

A node or box with `:md-ref "docs/api.md"` gets a dotted border, and
"open md" (`f m`) edits that doc in a panel beside the graph.

## More

- [md pages](guide.md#md-pages) — what renders, and how links follow.
- [Editing docs](guide.md#editing-docs) — the panel, saving, Format.
- [vi mode](guide.md#vi-mode) — every key it knows.
- [Presentations](presentations.md) — the same file as slides.
