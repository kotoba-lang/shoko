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
  client is NOT shipped here (inject your own).

  `r2-archiveport` (JVM-only, below) IS a real, opt-in ArchiveTarget backed
  by a Cloudflare R2 bucket via R2's S3-compatible API — mock stays the
  default everywhere (shoko.operation/build's :archiveport opt), real R2 is
  only ever used when a caller explicitly constructs and injects one.
  shoko is a plain JVM library, not a Cloudflare Worker, so it cannot use
  the R2Bucket binding app-aozora's PDS Worker uses (ADR-2607071000,
  `.put`/`.get` on an injected binding) — it must speak R2's S3-compatible
  API over plain HTTP instead, which means signing every request with AWS
  SigV4. `kotobase.sigv4` (gftdcojp/net-kotobase clj-edge) already has a
  proven SigV4 canonicalization for this exact account (B2 + R2 both speak
  S3), but it's ClojureScript-only (`js/encodeURIComponent` et al) and thus
  not `require`-able from the JVM host. The functions below are a faithful
  JVM port of that same algorithm (same function names/shapes, cross-
  checked against `kotobase.sigv4-test`'s golden vectors in
  archiveport_test.clj) plus the HMAC signing-key chain kotobase.sigv4
  deliberately leaves to the host's crypto (there: `crypto.subtle`; here:
  `javax.crypto.Mac`/`java.security.MessageDigest`, JDK-builtin — no AWS
  SDK, no extra deps). Kept JVM-only (`#?(:clj ...)`) rather than forced
  portable: shoko has no cljs/kototama build target today (deps.edn has no
  shadow-cljs; cli.clj/cacao.clj are already .clj-only) and Web Crypto's
  async API can't be ported synchronously anyway — same pragmatic call
  CLAUDE.md documents for kotoba-lang/ed25519 et al."
  ;; clojure.string/clojure.edn are used ONLY inside the #?(:clj ...) R2/SigV4
  ;; section below (see docstring above) — under a :cljs reading that whole
  ;; section vanishes, so clj-kondo's cljs-side pass would otherwise flag
  ;; these as unused (and a fully-conditional #?(:clj [...]) require, with no
  ;; :cljs branch, makes the :cljs ns's :require empty, which clj-kondo
  ;; rejects outright) — ^:clj-kondo/ignore is the correct scoped escape
  ;; hatch for a require that's genuinely host-specific by design.
  (:require ^:clj-kondo/ignore [clojure.string :as str]
            ^:clj-kondo/ignore [clojure.edn :as edn]))

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

;; ───────────────────────── R2 SigV4 (JVM-only, real opt-in) ─────────────────────────
;; Pure canonicalization, ported function-for-function from kotobase.sigv4
;; (cljs) — see the ns docstring. Cross-checked against the exact same
;; golden vectors as kotobase.sigv4-test in archiveport_test.clj.

#?(:clj
(defn s3-encode
  "URI-encode `s` the way AWS SigV4/S3 requires: percent-encode every UTF-8
  byte except the unreserved set A-Z a-z 0-9 - _ . ~ (RFC 3986 §2.3). Working
  byte-wise (not via java.net.URLEncoder, which is form/x-www-urlencoded —
  wrong alphabet, e.g. space -> '+') is what makes this correct without the
  `!'()*` manual fixups kotobase.sigv4's `js/encodeURIComponent`-based
  version needs."
  [^String s]
  (let [unreserved? (fn [b] (or (<= (int \A) b (int \Z)) (<= (int \a) b (int \z))
                                (<= (int \0) b (int \9))
                                (contains? #{(int \-) (int \_) (int \.) (int \~)} b)))
        sb (StringBuilder.)]
    (doseq [signed-b (.getBytes s "UTF-8")]
      (let [b (bit-and (int signed-b) 0xff)]
        (if (unreserved? b)
          (.append sb (char b))
          (.append sb (format "%%%02X" b)))))
    (.toString sb))))

#?(:clj
(defn s3-path
  "→ \"/<bucket>/<key/segments>\", each segment independently s3-encoded
  (slashes in `key` stay literal path separators, per S3 canonical-URI
  rules)."
  [bucket key]
  (str "/" (s3-encode bucket) "/" (str/join "/" (map s3-encode (str/split key #"/" -1))))))

#?(:clj
(defn canonical-query
  "`params` (a plain map, string/keyword keys) → sorted `k=v&…`, S3-encoded."
  [params]
  (->> params
       (sort-by (comp name key))
       (map (fn [[k v]] (str (s3-encode (name k)) "=" (s3-encode (str v)))))
       (str/join "&"))))

#?(:clj
(defn amz-date
  "An ISO-8601 instant string (NO fractional seconds, or exactly 3 digits —
  callers here always produce whole-second instants, see `sign-request`) →
  {:long \"yyyyMMdd'T'HHmmss'Z'\" :short \"yyyyMMdd\"}. Same regex kotobase.
  sigv4's `amz-date` uses (`[:-]|\\.\\d{3}` stripped)."
  [iso]
  (let [long-str (str/replace iso #"[:-]|\.\d{3}" "")]
    {:long long-str :short (subs long-str 0 8)})))

#?(:clj
(defn canonical-request
  "→ {:canonical str :signed-headers str}. `headers` is a map whose keys are
  already-lowercased header names (as `sign-request` builds them)."
  [method path qs headers payload-hash]
  (let [hks (sort (keys headers))
        signed-headers (str/join ";" hks)
        canonical-headers (str/join "" (map (fn [h] (str h ":" (get headers h) "\n")) hks))
        canonical (str/join "\n" [(str/upper-case (name method)) path qs canonical-headers
                                  signed-headers payload-hash])]
    {:canonical canonical :signed-headers signed-headers})))

#?(:clj
(defn credential-scope [short-date region] (str short-date "/" region "/s3/aws4_request")))

#?(:clj
(defn string-to-sign [long-date scope canonical-request-hash]
  (str/join "\n" ["AWS4-HMAC-SHA256" long-date scope canonical-request-hash])))

#?(:clj
(defn authorization-header [key-id scope signed-headers sig]
  (str "AWS4-HMAC-SHA256 Credential=" key-id "/" scope
       ", SignedHeaders=" signed-headers ", Signature=" sig)))

;; ── JDK crypto (HMAC chain kotobase.sigv4 leaves to crypto.subtle) ──────────

#?(:clj
(defn- utf8-bytes ^bytes [^String s] (.getBytes s "UTF-8")))

#?(:clj
(defn- hex-str [^bytes bs]
  (let [sb (StringBuilder.)]
    (doseq [b bs] (.append sb (format "%02x" (bit-and (int b) 0xff))))
    (.toString sb))))

#?(:clj
(defn sha256-hex
  "Lowercase hex SHA-256 digest of `bs` (a byte[]) — the `x-amz-content-
  sha256` / canonical-request payload hash."
  [^bytes bs]
  (hex-str (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))

#?(:clj
(defn- hmac-sha256 ^bytes [^bytes key-bytes ^bytes data]
  (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec. key-bytes "HmacSHA256"))
    (.doFinal mac data))))

#?(:clj
(defn- signing-key
  "The SigV4 derived signing key: HMAC chain secret -> date -> region ->
  service -> \"aws4_request\" (AWS SigV4 spec, unabbreviated — the standard
  4-step derivation, nothing approximated)."
  ^bytes [secret-access-key short-date region service]
  (-> (hmac-sha256 (utf8-bytes (str "AWS4" secret-access-key)) (utf8-bytes short-date))
      (hmac-sha256 (utf8-bytes region))
      (hmac-sha256 (utf8-bytes service))
      (hmac-sha256 (utf8-bytes "aws4_request")))))

#?(:clj
(defn sign-request
  "Build a SigV4-signed S3-compatible request map ({:url :method :headers
  :body}, ready for `jvm-http-fn`) for R2's S3 API.

  opts: :method (:get/:put/:delete), :bucket, :key, :query (map, default
  {}), :headers (extra request headers to sign + send, default {}),
  :payload (byte[], default empty — GETs have none), :access-key-id,
  :secret-access-key, :region (default \"auto\" — R2's SigV4 signing region,
  confirmed against a live bucket), :endpoint-host (e.g. \"<account-id>.r2.
  cloudflarestorage.com\"), :now (java.time.Instant, default now — truncated
  to whole seconds so `amz-date`'s fractional-seconds regex never sees more
  than kotobase.sigv4's assumed 3 digits)."
  [{:keys [method bucket key query headers payload access-key-id secret-access-key
           region endpoint-host now]
    :or {region "auto" query {} headers {} payload (byte-array 0)
         now (java.time.Instant/now)}}]
  (let [now-iso (-> now (.truncatedTo java.time.temporal.ChronoUnit/SECONDS) .toString)
        {:keys [long short]} (amz-date now-iso)
        payload-hash (sha256-hex payload)
        path (s3-path bucket key)
        qs (canonical-query query)
        signing-headers (assoc headers
                                "host" endpoint-host
                                "x-amz-content-sha256" payload-hash
                                "x-amz-date" long)
        {:keys [canonical signed-headers]} (canonical-request method path qs signing-headers payload-hash)
        cr-hash (sha256-hex (utf8-bytes canonical))
        scope (credential-scope short region)
        sts (string-to-sign long scope cr-hash)
        sig (hex-str (hmac-sha256 (signing-key secret-access-key short region "s3") (utf8-bytes sts)))
        auth (authorization-header access-key-id scope signed-headers sig)]
    {:url (str "https://" endpoint-host path (when (seq qs) (str "?" qs)))
     :method method
     :headers (assoc signing-headers "Authorization" auth)
     :body payload})))

;; ── HTTP transport (same {:url :method :headers :body} -> {:status :body}
;; convention as cloudflare.client/jvm-http-fn and cloud-itonami.mail/
;; jvm-http-fn — byte[] bodied here since R2 payloads are arbitrary bytes,
;; not JSON/text) ─────────────────────────────────────────────────────────

;; JDK java.net.http refuses to let callers set certain headers directly
;; (throws IllegalArgumentException: "restricted header name") — "host" is
;; one (confirmed against the live R2 endpoint, not just docs), since the
;; JDK derives it from the request URI itself instead. SigV4 still needs
;; "host" in the SIGNED header set (sign-request puts it there), it just
;; must not also be re-sent via .header() — the URI's authority already
;; carries the same value we signed, so the wire header matches regardless.
#?(:clj
(def ^:private restricted-headers #{"host" "connection" "content-length" "date" "expect"
                                    "from" "origin" "referer" "upgrade" "via" "warning"}))

#?(:clj
(defn jvm-http-fn
  ([] (jvm-http-fn {}))
  ([{:keys [timeout-seconds] :or {timeout-seconds 30}}]
   (fn [{:keys [url method headers body]}]
     (let [sendable-headers (into {} (remove (fn [[k _]] (restricted-headers (str/lower-case k)))) headers)
           builder (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                       (.timeout (java.time.Duration/ofSeconds timeout-seconds))
                       (as-> b (reduce-kv (fn [b k v] (.header ^java.net.http.HttpRequest$Builder b k v))
                                          b sendable-headers)))
           body-bytes (or body (byte-array 0))
           request (-> (case method
                        :put (.PUT ^java.net.http.HttpRequest$Builder builder
                                   (java.net.http.HttpRequest$BodyPublishers/ofByteArray body-bytes))
                        :get (.GET ^java.net.http.HttpRequest$Builder builder)
                        :delete (.DELETE ^java.net.http.HttpRequest$Builder builder)
                        (throw (ex-info "Unsupported HTTP method" {:method method})))
                      .build)
           resp (.send (java.net.http.HttpClient/newHttpClient) request
                      (java.net.http.HttpResponse$BodyHandlers/ofByteArray))]
       {:status (.statusCode resp) :body (.body resp)})))))

;; ───────────────────────── r2-archiveport (real, opt-in) ─────────────────────────

#?(:clj
(defn r2-archiveport
  "A REAL ArchiveTarget backed by a Cloudflare R2 bucket, via R2's S3-
  compatible API (plain HTTP + SigV4 — no AWS SDK, no Worker runtime).
  `fetch-file` = GET object; `share!` = PUT object (keyed by file-id) then
  call the injected distributor; `propose-revision!` stays a pure branch-
  name computation like mock-archiveport's — R2 has no branch concept and
  the protocol docstring is explicit that propose-revision! has 'no
  external effect yet' (only share!, after human approval, ever writes).

  opts:
    :bucket             R2 bucket name (e.g. \"cloud-itonami-shoko-archive\") — required.
    :account-id         Cloudflare account id (endpoint host is
                        \"<account-id>.r2.cloudflarestorage.com\") — falls
                        back to env R2_ACCOUNT_ID, throws if neither given.
    :access-key-id      R2 S3 Access Key ID — falls back to env
                        R2_ACCESS_KEY_ID, throws if neither given.
    :secret-access-key  R2 S3 Secret Access Key — falls back to env
                        R2_SECRET_ACCESS_KEY, throws if neither given.
    :region             SigV4 region — default \"auto\" (R2's own signing
                        region; confirmed against a live bucket).
    :key-prefix         object key prefix — default \"shoko/\".
    :http-fn            HTTP transport — default (jvm-http-fn). Inject a
                        stub in tests (see archiveport_test.clj) — the
                        automated suite never needs real creds/network.
    :distributor        fn share! calls with the delivered record, same
                        shape as mock-archiveport's — default no-op.

  Every env fallback is looked up lazily (only when the corresponding opt
  key is absent), so tests that pass explicit :access-key-id/:secret-
  access-key/:account-id never touch the environment at all."
  [{:keys [bucket account-id access-key-id secret-access-key region key-prefix http-fn distributor]
    :or {region "auto" key-prefix "shoko/" http-fn (jvm-http-fn) distributor (fn [_] nil)}}]
  (let [env-or-throw (fn [v] (or (System/getenv v) (throw (ex-info (str v " is not set") {}))))
        account-id (or account-id (env-or-throw "R2_ACCOUNT_ID"))
        access-key-id (or access-key-id (env-or-throw "R2_ACCESS_KEY_ID"))
        secret-access-key (or secret-access-key (env-or-throw "R2_SECRET_ACCESS_KEY"))
        endpoint-host (str account-id ".r2.cloudflarestorage.com")
        object-key (fn [file-id] (str key-prefix file-id ".edn"))
        call! (fn [{:keys [method key payload req-headers]}]
                (http-fn (sign-request {:method method :bucket bucket :key key
                                        :headers (or req-headers {}) :payload (or payload (byte-array 0))
                                        :access-key-id access-key-id :secret-access-key secret-access-key
                                        :region region :endpoint-host endpoint-host})))]
    (reify ArchiveTarget
      (fetch-file [_ file-id]
        (let [resp (call! {:method :get :key (object-key file-id)})]
          (cond
            (= 404 (:status resp)) nil
            (< (:status resp) 300) (edn/read-string (String. ^bytes (:body resp) "UTF-8"))
            :else (throw (ex-info "R2 GET failed" {:status (:status resp) :file-id file-id})))))
      (propose-revision! [_ file _content] {:branch (str "shoko/" (:drive/id file))})
      (share! [_ file-id principal content]
        (let [rec {:file-id file-id :principal principal :content content}
              resp (call! {:method :put :key (object-key file-id)
                          :payload (utf8-bytes (pr-str rec))
                          :req-headers {"content-type" "application/edn"}})]
          (when-not (< (:status resp) 300)
            (throw (ex-info "R2 PUT failed" {:status (:status resp) :file-id file-id})))
          (distributor rec)
          rec))))))
