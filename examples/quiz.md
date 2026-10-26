---

# Know the demo

## a quiz, presented

Present, top right, asks these questions with the demo graph beside them. Your answers go to `quiz.answers.edn`, next to this file.

---

> [the demo](demo.edn)
??? sessions

# Where does the API keep its sessions?

`???` is a quiz question: pick one, then Submit. → waits until you do.

- [ ] In Postgres
  > No: Postgres takes the API's queries, not its sessions.
- [x] In Redis
  > Right: the "session" edge runs both ways between API and Redis.
- [ ] In the Queue
  > No: the Queue carries work for the Worker.

---

> [the demo](demo.edn)
??? worker

# Where does the Worker get its work?

Submitted, the answer is locked: the right choice is marked, and the note under yours shows.

- [ ] From the API directly
  > No: there is no edge between the Worker and the API.
- [x] From the Queue
  > Right: it consumes what the API publishes there.
- [ ] From the Log Sink
  > No: the Worker writes to the Log Sink, it doesn't read from it.
- [ ] From the Mailer
  > No: the Worker sends mail through it.

---

> [the demo](demo.edn)
??? zone

# Which box holds Postgres and Redis?

A quiz question takes 2 to 4 choices.

- [ ] backend, directly
  > Almost: backend holds them, but inside another box.
- [x] storage
  > Right: storage, which sits inside backend.

---

> [the change](demo.edn?compare=next)
?? change

# Should the next version drop the Mailer?

The graph beside this text compares demo.edn with demo-next.edn: the Mailer, red and dashed, is gone; Metrics, green, is new.

- [ ] Yes, mail can go through another service
- [ ] No, the Worker still has mail to send
- [ ] Ask the team first

`??` asks without Submit: a click answers, another click changes it, and with no `[x]` it is a poll. Text after the list shows above the choices, like this line.

---

?? confidence

# How well do you know the demo now?

Another poll: any answer counts, none is marked right.

- [ ] I could draw it from memory
- [ ] I know the main paths
- [ ] I'd still need the graph

---

# Your answers

Every answer went to `quiz.answers.edn` as you gave it:

    {"sessions" {:answer "In Redis" :num 1}
     "confidence" {:answer "I know the main paths" :num 1}}

- `:num` counts the choices from 0
- An agent presenting this deck can read the file and write the next slide
- Presenting again starts from your answers: delete the file to ask again
