#!/usr/bin/env bb
;; r2-creds.bb — Cloudflare R2 S3-compatible credentials (Access Key ID /
;; Secret Access Key / Account ID) を解決して出力する。shoko.archiveport/
;; r2-archiveport の手動 live 検証専用（自動テスト `clojure -M:dev:test` は
;; injected fake :http-fn のみを使い、real credentials/network を一切
;; 必要としない — see test/shoko/archiveport_test.clj）。
;;
;; 解決順は下記 :order（既定 env→1Password）。cloud-itonami/scripts/
;; mail-creds.bb と同じ形: 参照先（op:// パス）は秘密ではないのでこの
;; ファイルに置く。実値は解決時のみ取得し、リポジトリには一切置かない。
;;
;; この gftd-r2/* の組は 2026-07-07 に実際の R2 バケット（S3 API 経由の
;; ListBuckets/PutObject/GetObject 往復）に対して認証成功を確認済み —
;; 同じ Cloudflare アカウント配下に平行して存在する gftd.r2/* という別名の
;; 組は未検証のため使わない（両方とも同じアカウントを指すが、動作確認
;; 済みなのは gftd-r2/* だけ）。
;;
;; 出力:
;;   bb scripts/r2-creds.bb            ; shell 用 export 行（eval して使う）
;;   bb scripts/r2-creds.bb --json     ; {"R2_ACCESS_KEY_ID":...,...}
;;   eval "$(bb scripts/r2-creds.bb)"  ; 環境に流し込む
(require '[clojure.string :as str]
         '[clojure.java.shell :refer [sh]]
         '[cheshire.core :as json])

(def secrets
  {"R2_ACCESS_KEY_ID"
   {:order [:env :1password]
    :env "R2_ACCESS_KEY_ID"
    :1password "op://gftdcojp/jabkybmh3yyhrbiu2nxnm4u4ru/password"}  ; gftd-r2/R2_ACCESS_KEY_ID
   "R2_SECRET_ACCESS_KEY"
   {:order [:env :1password]
    :env "R2_SECRET_ACCESS_KEY"
    :1password "op://gftdcojp/nhivnb5d4uu3xda7l6yxswllim/password"}  ; gftd-r2/R2_SECRET_ACCESS_KEY
   "R2_ACCOUNT_ID"
   {:order [:env :1password]
    :env "R2_ACCOUNT_ID"
    :1password "op://gftdcojp/fj7mafzukytakxxq6mt5337axi/password"}}) ; gftd-r2/R2_ACCOUNT_ID

(defn from-env [{:keys [env]}]
  (some-> env System/getenv (#(when-not (str/blank? %) %))))

(defn from-1password [cfg]
  (when-let [ref (get cfg :1password)]
    (let [{:keys [exit out]} (sh "op" "read" ref)]
      (when (zero? exit) (str/trim out)))))

(defn resolve-secret [{:keys [order] :as cfg}]
  (some (fn [src]
          (try
            (case src
              :env (from-env cfg)
              :1password (from-1password cfg)
              nil)
            (catch Exception _ nil)))
        order))

(let [vals (into {} (for [[name cfg] secrets] [name (resolve-secret cfg)]))
      missing (filter #(str/blank? (vals %)) (keys secrets))
      json? (some #{"--json"} *command-line-args*)]
  (when (seq missing)
    (binding [*out* *err*]
      (println (str "r2-creds: 解決できない項目: " (str/join ", " missing)
                    " (op に signin 済みか確認: `op whoami`)")))
    (System/exit 1))
  (if json?
    (println (json/generate-string vals))
    (doseq [[k v] vals]
      (println (str "export " k "='" (str/replace v "'" "'\\''") "'")))))
