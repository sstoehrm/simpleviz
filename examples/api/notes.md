# Notes on the API

A second md page, one folder down. Its links are relative to this file, as a `:ref` is,
and the trail at the top leads back to [the intro](../intro.md).

[The internals graph](internals.edn) zooms into the API node of [the demo](../demo.edn):
a request passes these components.

| Component   | Does                               | Attributes      |
| ----------- | ---------------------------------- | --------------- |
| Router      | matches the request's path         | `:lib "reitit"` |
| Handlers    | run it                             |                 |
| Auth client | verifies the caller, over gRPC     |                 |
| Repository  | loads and saves                    |                 |
| Conn pool   | lends the repository a connection  | `:size 10`      |

## Back up

The "Demo overview" node has `:ref "../demo.edn"`: `..` climbs to the demo's folder,
never above the served one. Select it and press `f r`.

## The next version

[The fork of the internals](internals-next.edn) is this graph after the change in
[the fork of the demo](../demo-next.edn). In a comparison, following the API's ref
compares these two files too.
