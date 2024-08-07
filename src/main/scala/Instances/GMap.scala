package Instances

import Types.CRDT

type GMap[A, B] = Map[A, B]

object GMap:
  def newGMap[A, B](m: Map[A, B]): GMap[A, B] = m

// instance (CRDT b d c) => CRDT (Gmap a b c) (Map a b) c where
given [A, B, C, D](using x: CRDT[B]): CRDT[GMap[A, B]] with
  extension (x: GMap[A, B])
    def \/(y: GMap[A, B]): GMap[A, B] =
      val keys = x.keySet union y.keySet
      val f = (k: A) =>
        (x.get(k), y.get(k)) match
          case (Some(a), Some(b)) => Some(a \/ b)
          case (Some(a), _)       => Some(a)
          case (_, Some(b))       => Some(b)
          case _                  => None
      val pairs = keys.map(k => f(k).map(v => (k, v)))
      Map.from(pairs.flatten())
