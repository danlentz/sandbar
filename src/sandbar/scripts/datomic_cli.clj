(ns sandbar.scripts.datomic-cli
  "Locate and memoize an executable Datomic CLI for administrative scripts.

   Discovery order: DATOMIC_BIN; DATOMIC_HOME/bin/datomic; datomic on PATH;
   conventional installations under $HOME/opt, /opt, or /usr/local. Scanned
   candidates prefer later names. Each candidate must be a regular executable
   file, so a stale explicit path can fall through to another strategy.

   A delay defers discovery until first use and caches it for the JVM lifetime.
   If no candidate works, print the attempted strategies and exit 4.
   Explicit configuration makes deployment selection repeatable; discovery
   alone does not verify the selected version's command compatibility."
  (:require [clojure.java.io    :as io]
            [clojure.java.shell :as sh]
            [clojure.string     :as str])
  (:import (java.io File)))

(defn getenv
  "Thin wrapper around `System/getenv` providing a `with-redefs`-able
   seam for tests.  Returns the value of env var `k`, or nil if unset.

   Why a wrapper: `System/getenv` is a static Java method; `with-redefs`
   only rebinds Clojure vars.  Routing all env reads through this fn
   lets tests stub the environment without process-level mutation."
  [k]
  (System/getenv k))

(defn executable?
  "True iff `path` names an existing, regular, executable file.
   `nil` and blank strings short-circuit to false."
  [path]
  (when (and path (string? path) (not (str/blank? path)))
    (let [f (io/file path)]
      (and (.exists f) (.isFile f) (.canExecute f)))))

(defn from-env-bin
  "Strategy 1: honor `DATOMIC_BIN` if it points at an executable file."
  []
  (let [p (getenv "DATOMIC_BIN")]
    (when (executable? p) p)))

(defn from-env-home
  "Strategy 2: honor `DATOMIC_HOME` conventional layout
   (`$DATOMIC_HOME/bin/datomic`)."
  []
  (when-let [home (getenv "DATOMIC_HOME")]
    (let [p (str home "/bin/datomic")]
      (when (executable? p) p))))

(defn from-path
  "Strategy 3: shell `which datomic` (POSIX); accept if the resolved
   path is executable."
  []
  (try
    (let [{:keys [exit out]} (sh/sh "which" "datomic")]
      (when (zero? exit)
        (let [p (str/trim out)]
          (when (executable? p) p))))
    (catch Throwable _ nil)))

(defn from-common-roots
  "Strategy 4: scan conventional install roots
   (`$HOME/opt/datomic*`, `/opt/datomic*`, `/usr/local/datomic*`).
   When multiple matches exist, prefers the lexicographically last
   directory name — for Datomic's `datomic-pro-1.0.7482` convention
   this matches the highest version."
  []
  (let [home  (System/getProperty "user.home")
        roots (cond-> ["/opt" "/usr/local"]
                home (conj (str home "/opt")))]
    (->> (for [root roots
               :let  [rdir (io/file root)]
               :when (.isDirectory rdir)
               sub   (.listFiles rdir)
               :when (and (.isDirectory ^File sub)
                          (str/starts-with? (.getName ^File sub) "datomic"))]
           (str (.getCanonicalPath ^File sub) "/bin/datomic"))
         (filter executable?)
         sort
         last)))

(def ^:private resolved
  (delay
    (or (from-env-bin)
        (from-env-home)
        (from-path)
        (from-common-roots))))

(defn datomic-bin
  "Return the absolute path to the Datomic CLI binary.

   On total discovery failure, prints an actionable multi-line error to
   `*err*` (naming every strategy tried + the env var to set) and exits
   the JVM with code 4.  Memoized via `delay` — discovery runs at most
   once per JVM."
  []
  (or @resolved
      (binding [*out* *err*]
        (println "ABORT: cannot locate the Datomic CLI binary.  Tried (in order):")
        (println "  1. $DATOMIC_BIN env var (set to absolute path of `datomic`)")
        (println "  2. $DATOMIC_HOME/bin/datomic (set DATOMIC_HOME)")
        (println "  3. `datomic` on $PATH")
        (println "  4. $HOME/opt/datomic*/bin/datomic,")
        (println "     /opt/datomic*/bin/datomic,")
        (println "     /usr/local/datomic*/bin/datomic")
        (println)
        (println "Fix: export DATOMIC_BIN=/absolute/path/to/datomic")
        (println "  (or: export DATOMIC_HOME=/path/to/datomic-install-root)")
        (System/exit 4))))
