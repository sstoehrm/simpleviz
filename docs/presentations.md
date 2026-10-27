# Presentations

Any md file is also a slide deck: Present (top right of the page) shows
it one slide at a time. A line of just `---` starts the next slide; a
slide whose first line is `>` and a link shows that graph, md file or
picture to the right of its text:

    ---
    # Payments
    ## how a charge flows
    the platform team

    ---
    > [the flow](pay/flow.edn)
    # The flow

    - the API validates, the worker charges
    ---
    > [the change](pay/flow.edn?compare=next)
    # What changes

To start one:

    simpleviz init talk.md -t presentation   # a deck, plus talk.edn for its graph slide
    simpleviz talk.md

`simpleviz demo` brings `deck.md`, a deck about decks, and `quiz.md`.
[50-second video](assets/whats-new.mp4) ([gif](assets/whats-new.gif)).

- The first slide is the title slide, centered — unless it has a `>` link.
- → ↓ space step forward, ← ↑ back, Home and End jump; Esc leaves.
- A graph beside a slide can be hovered, inspected, panned and zoomed,
  but not edited.
- The URL keeps the slide, and the text follows the file on disk while
  you present.
- Only a `>` line that is just that one link counts, and only when the
  next line doesn't go on with `>`; any other `>` line is a quote, as in
  markdown. `>>` works as before, a plain `>> path` too.
- A slide can ask a question: see [Quizzes](quizzes.md).
- `simpleviz export talk.md talk.pdf` writes the deck as a PDF, a 16:9
  page per slide, with what each `>` shows beside it — and a page per
  step of a graph with `:steps`.

## More

- [Presenting](guide.md#presenting) — every rule and key.
