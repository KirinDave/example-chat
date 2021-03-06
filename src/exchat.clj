(ns exchat
    (:use [clojure.contrib server-socket duck-streams str-utils])
    (:gen-class))

;;; This code provides asycnhronous I/O for rooms. 
(def rooms (ref {}))
(defn make-get-room! [name] 
  (dosync
   (or (@rooms name)
       (let [room (agent (list))]
         (do (alter rooms assoc name room) room)))))

(defn agent-write-room [room message]
  (doseq [[_ output] room]   
      (binding [*out* output]
        (println message)))
  room)                                 ; We leave the room unaltered

(defn agent-add-user-room [room username output]
  (conj room [username output]))
(defn agent-remove-user-room [room username]
  (remove #(= (% 0) username) room))

;;; This code is for the synchronous server loop each connection engages.
;;; It uses bindings for clarity.

(def *username* "Someone")
(defn- join-room [room] 
  (send room agent-add-user-room *username* *out*)
  (send room agent-write-room (str ":: " *username* " has joined the room.")))
(defn- leave-room [room]
  (send room agent-remove-user-room *username*)
  (send room agent-write-room (str ":: " *username* " has left the room.")))
(defn- say [room message]
  (send room agent-write-room (str *username* ": " message)))
(defn- display-users [roomname]
  (println "--- Users in" roomname)
  (doseq [user @(make-get-room! roomname)] (println "* " (user 0))))
(defn- cleanup []
  (doseq [room (vals @rooms)]
      (when-first [active-room (filter #(= *out* (% 1)) @room)]
                  (send room agent-write-room (str *username* " has disconnected.")))
      (send room agent-remove-user-room *username*)))

;;; Main and client-loop do the heavy lifting of making a server
;;; and running it. 

(defn- client-loop [in out]
  (binding [*in* (reader in)
            *out* (writer out)]
    (print "\nWhat is your name? ") (flush)
    (binding [*username* (read-line)]
      (join-room (make-get-room! "default"))
      (try (loop [input (read-line) roomname "default"]
             (when input 
               (let [splitform (re-split #" " input)
                     cmd (first splitform)
                     rest-str (apply str (rest splitform))
                     room (make-get-room! roomname)]
                 (cond
                  (= cmd "/join") (do (leave-room room) 
                                      (join-room (make-get-room! rest-str)) 
                                      (flush)
                                      (recur (read-line) rest-str))
                  (= cmd "/who")  (do (display-users room) (flush)
                                      (recur (read-line) roomname))
                  (= cmd "/quit") :done
                  true            (do (when-not (empty? input) (say room input) (flush))
                                      (recur (read-line) roomname))))))
           (finally (cleanup))))))
 
(defn -main
  ([port] (println "Launching Exchant server on port" port)
          (def server-instance (create-server (Integer. port) client-loop)))
  ([]     (-main 6060)))