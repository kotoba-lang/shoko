# shoko (書庫)

> **Status: proposed, scaffold-only.** This repo reserves the name, registers
> the dependency shape, and records the future design intent. There is no
> governor, no StateGraph, and no operation logic here yet — see
> [ADR-2607062020](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607062020-kotoba-lang-shoko-archive-scaffold.md)
> for full context and the follow-up execution plan.

shoko (書庫, "archive/repository") will eventually be the **storage-governance
actor** guarding access to
[`kotoba-lang/drive`](https://github.com/kotoba-lang/drive)'s file/folder EDN
model. `drive.model` today has zero ACL/sharing concept (confirmed by grep —
`acl|access-control|permission|share` is a zero-hit search there); shoko is
meant to fill exactly that gap, in the same **sealed-intelligence ⊣
independent-governor** StateGraph pattern as
[`kekkai`](https://github.com/kotoba-lang/kekkai) (coord-LLM ⊣ TailnetGovernor)
and `cloud-itonami` (ops-LLM ⊣ CertGovernor).

## Future design (not yet implemented)

The following is the design *direction* recorded in the ADR for a future
follow-up — none of it exists in this repo yet beyond docstring stubs.

- **ArchiveGovernor** HARD invariant candidates:
  - `no-actuation` — proposals stay `:draft` only; the governor, not the
    intelligence node, ever commits a real state change.
  - `share-requires-acl` — `drive.model` has no ACL concept of its own, so
    shoko must hold that concept itself and treat any share attempt without an
    explicit grant as a hard violation.
  - `tenant-isolation` — no cross-tenant visibility of archived items.
- **ArchivePort** protocol candidates: `fetch-file`, `propose-revision!`,
  `share!` (the last only ever executes post-approval).

## Layout (scaffold)

| Path | Role |
|---|---|
| `src/shoko/model.cljc` | stub — future draft type for archive artifacts over `drive.model` file/folder |
| `src/shoko/governor.cljc` | stub — future ArchiveGovernor invariants |
| `src/shoko/archiveport.cljc` | stub — future ArchivePort protocol |
| `docs/DESIGN.md` | restates the ADR design intent, marked NOT YET IMPLEMENTED |
| `test/shoko/smoke_test.clj` | one test confirming the stub namespaces load |

## Run

```bash
clojure -M:test    # smoke test only — no sim.cljc yet, no :run alias
clojure -M:lint     # clj-kondo (errors fail)
```

## References

- [ADR-2607062020](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607062020-kotoba-lang-shoko-archive-scaffold.md) — this repo's own scaffold ADR
- [`kotoba-lang/drive`](https://github.com/kotoba-lang/drive) — the file/folder model shoko will govern access to
- [`kotoba-lang/kekkai`](https://github.com/kotoba-lang/kekkai) — the sealed-intelligence ⊣ independent-governor pattern shoko will follow
