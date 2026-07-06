(ns shoko.governor
  "Status: proposed — 雛形のみ。governor/operation の実装は follow-up
  （ADR-2607062020）。将来の HARD 不変条件候補: `no-actuation`（proposal は
  `:draft` のみ）、`share-requires-acl`（drive.model 自体に無い ACL 概念を
  shoko 側で持ち、未許可の共有を hard violation にする）、`tenant-isolation`。")

(defn placeholder []
  :not-implemented)
