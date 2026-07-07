(ns shoko.archiveport
  "ArchiveTarget port — the ONLY place a file actually leaves the building.
  An archive-LLM proposal is data (a `:draft` record) until a human approves
  sharing it; `share!` is called exactly once, after that approval, by
  `shoko.operation`'s commit step — the actuation (granting `principal`
  access + handing the delivered content to an injected Distributor).
  `propose-revision!` is the 'casual commit' analog (teian.deckport's/
  koyomi.scheduleport's): recording that an archival draft candidate exists,
  no external effect yet.

  `mock-archiveport` is the default — a deterministic in-memory target so
  the actor is runnable/testable with no network/creds. A real
  implementation would still call an injected Distributor fn (e.g. an email/
  notification API telling `principal` they now have access) for actual
  delivery, same injection shape as kekkai/teian/koyomi's ports — a live
  client is NOT shipped here (inject your own)."
  )

(defprotocol ArchiveTarget
  (fetch-file [ap file-id] "the file's most recently shared content, or nil")
  (propose-revision! [ap file content]
    "record `content` (a drive.model file/folder EDN item) as a proposed
    archival revision for `file` (the file's full ground-fact record) — not
    yet shared. Returns a map (e.g. {:branch ...}) to be merged onto the
    draft so a later :file/share knows the draft was proposed against a real
    target.")
  (share! [ap file-id principal content]
    "grant `principal` access to `content` (the already human-approved,
    checkpointed drive.model EDN — NEVER a fresh store re-read, see
    shoko.operation/commit-effects!) and hand it to the target's injected
    distributor for actual delivery — the actuation. Only ever called after
    human approval."))

;; ───────────────────────── mock (default, runnable offline) ─────────────────────────

(defn mock-archiveport
  "A deterministic in-memory ArchiveTarget: `shared` is an atom of
  {file-id -> {:file-id :principal :content}} so tests/sim can assert on
  what WOULD have been shared, without any network call. `distributor` is
  the injected fn `share!` calls with that same map for actual delivery —
  the default is a no-op (a real Distributor — email/Slack/etc — is caller-
  injected; not shipped here)."
  ([] (mock-archiveport (atom {}) (fn [_] nil)))
  ([shared] (mock-archiveport shared (fn [_] nil)))
  ([shared distributor]
   (reify ArchiveTarget
     (fetch-file [_ file-id] (get @shared file-id))
     (propose-revision! [_ file _content] {:branch (str "shoko/" (:drive/id file))})
     (share! [_ file-id principal content]
       (let [rec {:file-id file-id :principal principal :content content}]
         (distributor rec)
         (swap! shared assoc file-id rec)
         rec)))))
