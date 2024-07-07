import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.*

import java.util.concurrent.Semaphore

import flatspec.*
import matchers.*

class MVar[A](
    var v: Option[A],
    lock1: Semaphore,
    lock2: Semaphore
):
  def put(x: A): Unit =
    lock1.release(1)
    v = Some(x)
    println("MVar put")
    lock2.release(1)

  def get(): A =
    lock1.acquire(1)
    lock2.acquire(1)
    println("MVar get")
    val a = v match
      case Some(x) => x
      case None    => ???
    v = None
    a

object MVar:
  def newMVar[A]: MVar[A] =
    var lock1 = new Semaphore(1)
    var lock2 = new Semaphore(1)
    lock1.acquire(1)
    lock2.acquire(1)
    println("newMVar")
    MVar(None, lock1, lock2)

class ActorSpec extends AnyFlatSpec with should.Matchers:
  it should "wait for windows" in:
    var result: MVar[Set[Int]] = MVar.newMVar
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg >= 5 then
            for {
              _ <- nextWindow[GSet[Int], Int]
              v <- await[GSet[Int], Int](0)
              _ <- liftIO[GSet[Int], Int, Unit](result.put(v))
            } yield ()
          else point(())
      } yield ()

    val handle2: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg >= 6 then nextWindow[GSet[Int], Int]
          else point(())
      } yield ()

    val system = ActorSystem(
      ActorMain.init[GSet[Int], Int](Set.empty)(
        List(handle1 -> Stream(1, 3, 5), handle2 -> Stream(2, 4, 6))
      ),
      "TestSystem"
    )

    assert(result.get() == Set(1, 3, 5, 2, 4, 6))

  it should "not wait if already has the value" in:
    var result: MVar[Set[Int]] = MVar.newMVar
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg == 10 || msg == 20 then nextWindow[GSet[Int], Int]
          else point(())
        _ <-
          if msg == 20 then
            for {
              _ <- await[GSet[Int], Int](1)
              v <- await[GSet[Int], Int](0)
              _ <- liftIO[GSet[Int], Int, Unit](result.put(v))
            } yield ()
          else point(())
      } yield ()

    val handle2: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <- nextWindow[GSet[Int], Int]
      } yield ()

    val system = ActorSystem(
      ActorMain.init[GSet[Int], Int](Set.empty)(
        List(handle1 -> Stream(6, 10, 20), handle2 -> Stream(1, 4))
      ),
      "TestSystem"
    )

    assert(result.get() == Set(1, 6, 10))

  it should "clear queue after continuing" in:
    var result: MVar[Set[Int]] = MVar.newMVar
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg % 10 == 0 then
            for {
              _ <- nextWindow[GSet[Int], Int]
              v <- await[GSet[Int], Int](0)
            } yield ()
          else point(())
        _ <-
          if msg == 20 then
            for {
              v <- await[GSet[Int], Int](1)
              _ <- liftIO[GSet[Int], Int, Unit](result.put(v))
            } yield ()
          else point(())
      } yield ()

    val handle2: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <- nextWindow[GSet[Int], Int]
      } yield ()

    val system = ActorSystem(
      ActorMain.init[GSet[Int], Int](Set.empty)(
        List(handle1 -> Stream(1, 10, 15, 20), handle2 -> Stream(4, 6))
      ),
      "TestSystem"
    )

    assert(result.get() == Set(1, 10, 15, 20, 4, 6))
