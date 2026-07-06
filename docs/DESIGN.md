# shoko Design — storage-governance actor (NOT YET IMPLEMENTED)

**Status: proposed — 雛形のみ.** Everything below restates the design
*intent* recorded in
[ADR-2607062020](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607062020-kotoba-lang-shoko-archive-scaffold.md).
None of it is implemented in this repo yet; `src/shoko/*.cljc` are docstring
stubs with a single placeholder function each. Real implementation is an
explicit follow-up (a separate ADR or an addendum to this one), not part of
this scaffold.

## Why shoko

[`kotoba-lang/drive`](https://github.com/kotoba-lang/drive) models drives,
folders, and files as pure EDN (`drive.model`: `drive`, `folder`, `file`,
`add-child`, `children`, ...) with **zero ACL/sharing concept** — confirmed by
grepping the model for `acl|access-control|permission|share` (zero hits) and
noting that `drive.model`'s own validation is purely structural. Before any
`teian`/`koyomi`-produced artifact (deck, ICS, etc.) can be shared externally,
something needs to govern "who can see what" over that model. shoko is meant
to be that governor.

## Future ArchiveGovernor (candidate HARD invariants)

Following the same **sealed-intelligence ⊣ independent-governor** StateGraph
shape as `kekkai` (coord-LLM ⊣ TailnetGovernor) and `cloud-itonami`
(ops-LLM ⊣ CertGovernor):

- `no-actuation` — a proposal from the intelligence node stays `:draft`;
  only the governor's verdict can turn it into a committed state change.
- `share-requires-acl` — since `drive.model` itself has no ACL concept,
  shoko must hold that concept on its own side and treat any `share!` without
  an explicit, resolvable grant as a hard violation (not just a soft
  low-confidence signal).
- `tenant-isolation` — no archived item, revision, or share grant is visible
  across tenant boundaries.

## Future ArchivePort (candidate protocol)

- `fetch-file` — read access to a `drive.model` file/folder subtree.
- `propose-revision!` — the intelligence node's only write surface; always
  produces a `:draft` proposal, never a committed revision.
- `share!` — the only operation that can grant external visibility, and only
  ever runs *after* governor approval (never directly reachable from a
  proposal).

## Non-goals of this scaffold

- No `store.cljc`, `operation.cljc`, `phase.cljc` StateGraph wiring.
- No CACAO self-mint / kotoba-server wiring (contrast with `kekkai`, which has
  this fully built).
- No `:run` alias — there is no driver/sim to run yet.
- No wiring from `cloud-itonami` or any other actor into shoko.
