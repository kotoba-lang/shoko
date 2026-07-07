(ns shoko.archiveport-test
  "r2-archiveport — the real, opt-in Cloudflare R2 ArchiveTarget. Two layers:

  1. SigV4 canonicalization golden vectors, identical to kotobase.sigv4-test
     (gftdcojp/net-kotobase clj-edge) — locks the JVM port to the exact same
     proven string format the ClojureScript original signs, byte for byte.
  2. r2-archiveport request-building against an INJECTED FAKE :http-fn (a
     plain closure capturing the request map, same convention
     cloudflare.client-test / cloud_itonami.mail-test use) — proves share!/
     fetch-file build well-formed SigV4-signed S3 requests (bucket/key/
     method/signed headers) with ZERO real network or credentials. The live
     round-trip against the actual R2 bucket is a separate manual
     verification step (see the ADR/task notes), never part of this suite."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shoko.archiveport :as ap]))

;; ───────────────────────── SigV4 golden vectors (kotobase.sigv4-test parity) ─────────────────────────

(deftest s3-encoding
  (is (= "a%20b" (ap/s3-encode "a b")))
  (is (= "a%2Ab" (ap/s3-encode "a*b")) "* -> %2A (S3 extra-encode)")
  (is (= "a%21b" (ap/s3-encode "a!b")))
  (is (= "%28x%29" (ap/s3-encode "(x)")))
  (testing "s3-path encodes bucket + each key segment, keeps slashes"
    (is (= "/my-bucket/pins/did%3Akey/abc.json"
           (ap/s3-path "my-bucket" "pins/did:key/abc.json")))))

(deftest query-canonicalization
  (is (= "" (ap/canonical-query {})))
  (testing "sorted by key, S3-encoded"
    (is (= "list-type=2&prefix=a%2Fb"
           (ap/canonical-query {:prefix "a/b" :list-type "2"})))))

(deftest dates
  (let [d (ap/amz-date "2026-01-02T03:04:05.678Z")]
    (is (= "20260102T030405Z" (:long d)))
    (is (= "20260102" (:short d)))))

(deftest canonical-and-string-to-sign
  (let [headers {"host" "s3.us-west.backblazeb2.com"
                 "x-amz-content-sha256" "abc123"
                 "x-amz-date" "20260102T030405Z"}
        cr (ap/canonical-request "GET" "/bucket/key" "" headers "abc123")]
    (is (= "host;x-amz-content-sha256;x-amz-date" (:signed-headers cr)))
    (is (= (str "GET\n/bucket/key\n\n"
                "host:s3.us-west.backblazeb2.com\n"
                "x-amz-content-sha256:abc123\n"
                "x-amz-date:20260102T030405Z\n\n"
                "host;x-amz-content-sha256;x-amz-date\n"
                "abc123")
           (:canonical cr))))
  (is (= "20260102/us-west/s3/aws4_request" (ap/credential-scope "20260102" "us-west")))
  (is (= "AWS4-HMAC-SHA256\n20260102T030405Z\n20260102/us-west/s3/aws4_request\nHASH"
         (ap/string-to-sign "20260102T030405Z" "20260102/us-west/s3/aws4_request" "HASH")))
  (is (= (str "AWS4-HMAC-SHA256 Credential=KID/20260102/us-west/s3/aws4_request, "
              "SignedHeaders=host;x-amz-date, Signature=DEAD")
         (ap/authorization-header "KID" "20260102/us-west/s3/aws4_request" "host;x-amz-date" "DEAD"))))

(deftest sha256-hex-known-vectors
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (ap/sha256-hex (byte-array 0)))
      "SHA-256 of the empty string — the well-known vector, and R2 GET's payload hash")
  (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
         (ap/sha256-hex (.getBytes "hello" "UTF-8")))))

;; ───────────────────────── sign-request shape ─────────────────────────

(deftest sign-request-builds-a-path-style-signed-request
  (let [req (ap/sign-request {:method :put :bucket "cloud-itonami-shoko-archive"
                              :key "shoko/f-handbook.edn"
                              :payload (.getBytes "hello" "UTF-8")
                              :headers {"content-type" "application/edn"}
                              :access-key-id "AKID" :secret-access-key "SECRET"
                              :endpoint-host "acct123.r2.cloudflarestorage.com"
                              :now (java.time.Instant/parse "2026-01-02T03:04:05Z")})]
    (is (= "https://acct123.r2.cloudflarestorage.com/cloud-itonami-shoko-archive/shoko/f-handbook.edn"
           (:url req)))
    (is (= :put (:method req)))
    (is (= "acct123.r2.cloudflarestorage.com" (get-in req [:headers "host"])))
    (is (= (ap/sha256-hex (.getBytes "hello" "UTF-8"))
           (get-in req [:headers "x-amz-content-sha256"]))
        "the signed content hash must match the actual payload bytes")
    (is (str/starts-with? (get-in req [:headers "Authorization"])
                          "AWS4-HMAC-SHA256 Credential=AKID/20260102/auto/s3/aws4_request, SignedHeaders=")
        "region defaults to \"auto\" (R2's SigV4 signing region)")
    (is (str/includes? (get-in req [:headers "Authorization"]) "content-type;host;x-amz-content-sha256;x-amz-date")
        "content-type was passed as an extra header, so it must be part of SignedHeaders too")))

(deftest sign-request-get-has-empty-payload-hash
  (let [req (ap/sign-request {:method :get :bucket "b" :key "k"
                              :access-key-id "AKID" :secret-access-key "SECRET"
                              :endpoint-host "acct.r2.cloudflarestorage.com"
                              :now (java.time.Instant/parse "2026-01-02T03:04:05Z")})]
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (get-in req [:headers "x-amz-content-sha256"])))))

;; ───────────────────────── r2-archiveport against an injected fake :http-fn ─────────────────────────

(defn- stub-http-fn
  "A plain closure capturing every request it's called with (in call order)
  and returning canned {:status :body} responses in that same order — the
  same fake-http convention cloudflare.client-test / cloud_itonami.mail-test
  use, so the automated suite never touches the network."
  [& responses]
  (let [calls (atom [])
        remaining (atom (or (seq responses) [{:status 200 :body (byte-array 0)}]))]
    {:calls calls
     :http-fn (fn [req]
                (swap! calls conj req)
                (let [resp (first @remaining)]
                  (when (next @remaining) (swap! remaining next))
                  resp))}))

(deftest share!-puts-the-content-and-calls-the-distributor
  (let [{:keys [calls http-fn]} (stub-http-fn {:status 200 :body (byte-array 0)})
        distributed (atom nil)
        target (ap/r2-archiveport {:bucket "cloud-itonami-shoko-archive" :account-id "acct123"
                                   :access-key-id "AKID" :secret-access-key "SECRET"
                                   :http-fn http-fn :distributor #(reset! distributed %)})
        content {:drive/id "f-handbook" :drive/kind :file :drive/title "従業員ハンドブック"}
        rec (ap/share! target "f-handbook" "alice" content)]
    (is (= {:file-id "f-handbook" :principal "alice" :content content} rec))
    (is (= rec @distributed) "share! must call the distributor with the exact delivered record")
    (testing "the captured request"
      (let [req (first @calls)]
        (is (= 1 (count @calls)))
        (is (= :put (:method req)))
        (is (= "https://acct123.r2.cloudflarestorage.com/cloud-itonami-shoko-archive/shoko/f-handbook.edn"
               (:url req)))
        (is (= "application/edn" (get-in req [:headers "content-type"])))
        (is (str/starts-with? (get-in req [:headers "Authorization"]) "AWS4-HMAC-SHA256 Credential=AKID/"))
        (is (= {:file-id "f-handbook" :principal "alice" :content content}
               (edn/read-string (String. ^bytes (:body req) "UTF-8")))
            "the PUT body is the exact same record share! returns/hands to the distributor")))))

(deftest share!-throws-on-a-non-2xx-status-and-never-calls-the-distributor
  (let [{:keys [http-fn]} (stub-http-fn {:status 500 :body (.getBytes "boom" "UTF-8")})
        distributed (atom :untouched)
        target (ap/r2-archiveport {:bucket "b" :account-id "a" :access-key-id "AKID"
                                   :secret-access-key "SECRET" :http-fn http-fn
                                   :distributor #(reset! distributed %)})]
    (is (thrown? clojure.lang.ExceptionInfo (ap/share! target "f1" "alice" {:drive/id "f1"})))
    (is (= :untouched @distributed) "a failed PUT must never reach the distributor")))

(deftest fetch-file-gets-and-decodes-the-stored-record
  (let [stored {:file-id "f-handbook" :principal "alice" :content {:drive/id "f-handbook"}}
        {:keys [calls http-fn]} (stub-http-fn {:status 200 :body (.getBytes (pr-str stored) "UTF-8")})
        target (ap/r2-archiveport {:bucket "cloud-itonami-shoko-archive" :account-id "acct123"
                                   :access-key-id "AKID" :secret-access-key "SECRET" :http-fn http-fn})]
    (is (= stored (ap/fetch-file target "f-handbook")))
    (let [req (first @calls)]
      (is (= :get (:method req)))
      (is (= "https://acct123.r2.cloudflarestorage.com/cloud-itonami-shoko-archive/shoko/f-handbook.edn"
             (:url req))))))

(deftest fetch-file-returns-nil-on-a-404
  (let [{:keys [http-fn]} (stub-http-fn {:status 404 :body (.getBytes "<Error/>" "UTF-8")})
        target (ap/r2-archiveport {:bucket "b" :account-id "a" :access-key-id "AKID"
                                   :secret-access-key "SECRET" :http-fn http-fn})]
    (is (nil? (ap/fetch-file target "missing")))))

(deftest fetch-file-throws-on-a-non-404-error-status
  (let [{:keys [http-fn]} (stub-http-fn {:status 403 :body (.getBytes "<Error/>" "UTF-8")})
        target (ap/r2-archiveport {:bucket "b" :account-id "a" :access-key-id "AKID"
                                   :secret-access-key "SECRET" :http-fn http-fn})]
    (is (thrown? clojure.lang.ExceptionInfo (ap/fetch-file target "f1")))))

(deftest propose-revision!-is-pure-no-network-call
  (let [calls (atom 0)
        http-fn (fn [_req] (swap! calls inc) {:status 200 :body (byte-array 0)})
        target (ap/r2-archiveport {:bucket "b" :account-id "a" :access-key-id "AKID"
                                   :secret-access-key "SECRET" :http-fn http-fn})]
    (is (= {:branch "shoko/f-handbook"}
           (ap/propose-revision! target {:drive/id "f-handbook"} {:drive/id "f-handbook"})))
    (is (zero? @calls) "propose-revision! must have no external effect (only share! writes)")))

;; NOTE: r2-archiveport's env-var credential fallback (R2_ACCESS_KEY_ID/
;; R2_SECRET_ACCESS_KEY/R2_ACCOUNT_ID, "throws if neither given") is
;; intentionally NOT covered here — asserting the throw requires those env
;; vars to be ABSENT, which would make this suite flaky in any shell that has
;; already `eval`'d scripts/r2-creds.bb (exactly the manual-verification
;; workflow this port exists for). Every test above passes explicit
;; :access-key-id/:secret-access-key/:account-id, which always takes
;; precedence over env and so is unaffected either way.
