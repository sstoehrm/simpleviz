# Quizzes

A [presentation](presentations.md) slide whose first line is `?? name`
asks a question: its task list gives the choices, shown as boxes under
the text, and `[x]` marks the right one. Lines indented under a choice
explain it.

    ---
    ??? storage
    # Where should session state live?

    - [ ] In each node's memory
      > Gone on restart, and the other nodes never see it.
    - [x] In Redis, shared by all nodes
      > Right: every node reads the same session.
    ---
    ?? confidence
    # How confident are you in the design?

    - [ ] Very
    - [ ] Not at all

- `??` takes a click at any time, and a new click changes the answer.
- `???` asks for Submit, and the deck waits for it. Submitted, the
  answer is locked, the right choice is marked and the explanation of
  the one picked shows.
- A question without an `[x]` is a poll.
- A question takes 2 to 4 choices. `simpleviz check talk.md` reports
  one with fewer or more, or without a name.

The answers go to `talk.answers.edn` beside `talk.md` as you give them,
so an agent presenting the deck can read them and write the next slide:

    {"storage" {:answer "In Redis, shared by all nodes" :num 1}}

Presenting again starts from that file; delete it to ask again.
`simpleviz demo` brings `quiz.md`, a quiz on the demo graph with every
kind of question.

## More

- [Questions](guide.md#questions) — every rule.
