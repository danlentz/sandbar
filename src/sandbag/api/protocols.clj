(ns socialnet.api.protocols)

(defprotocol EndPoint
  (make-request [self op lambda args])
  (execute      [self request])
  (decode       [self response]))

(defrecord Request  [id op lambda args])

(defrecord Response [request ex head body])
