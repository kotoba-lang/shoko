(ns shoko.smoke-test
  "Scaffold-only smoke test (ADR-2607062020): confirms the stub namespaces
  load without error. No governor/operation logic exists yet to test."
  (:require [clojure.test :refer [deftest is]]
            [shoko.model :as model]
            [shoko.governor :as governor]
            [shoko.archiveport :as archiveport]))

(deftest namespaces-load
  (is (= :not-implemented (model/placeholder)))
  (is (= :not-implemented (governor/placeholder)))
  (is (= :not-implemented (archiveport/placeholder))))
