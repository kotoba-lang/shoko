(ns shoko.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [shoko.query :as query]
            [shoko.store :as store]))

(defn- backends [] [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest draft-status-and-shared-with?
  (doseq [[label s] (backends)]
    (testing label
      (is (= "none" (query/draft-status s "f-handbook")) "no draft proposed yet")
      (store/record-datom! s {:kind :draft :id "f-handbook" :value {:status :proposed}})
      (is (= "proposed" (query/draft-status s "f-handbook")))
      (is (= "none" (query/draft-status s "f-never-drafted")))

      (is (query/shared-with? s "alice" "f-handbook") "demo data pre-seeds alice's grant")
      (is (not (query/shared-with? s "alice" "f-contract")) "no grant on a different file yet")
      (is (not (query/shared-with? s "mallory-external" "f-handbook"))))))

(deftest known-principal?
  (doseq [[label s] (backends)]
    (testing label
      (is (query/known-principal? s "alice") "pre-seeded via an existing grant")
      (is (not (query/known-principal? s "mallory-external"))
          "deny-by-default: an entirely unregistered principal is unknown"))))
