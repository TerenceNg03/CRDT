import Instances.{*, given}
import MVar.newMVar
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.*
import org.scalatest.tagobjects.Slow

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Random

import flatspec.*
import matchers.*

val conf = ConfigFactory.parseString("""
  pekko {
    log-dead-letters = 0
    log-dead-letters-during-shutdown = off
  }
""")

val nMsg = 2000
class ActorCrushPressureTest extends AnyFlatSpec with should.Matchers:
  it should "handle crashes" taggedAs (Slow) in:
    val mvar: MVar[Int] = newMVar
    val errorCount: AtomicInteger = new AtomicInteger(0)
    val handle: HandleM[GCounter[Int, ProcId], Int, IntRange, Unit] =
      for {
        msg <- getMsg
        procId <- getProcId
        _ <- modifyCRDT[GCounter[Int, ProcId], Int, IntRange](gs =>
          gs.increase(procId)(msg)
        )
        _ <-
          if msg % 40 == 0 && Random.nextDouble() > 0.8 then
            for {
              _ <- liftIO(errorCount.incrementAndGet())
              _ <- error("TestCrash")
            } yield ()
          else pure(())
        _ <-
          if msg % 20 == 0 then
            for {
              _ <- nextWindow[GCounter[Int, ProcId], Int, IntRange]
              v <- await[GCounter[Int, ProcId], Int, IntRange](msg / 20)
              _ <-
                if msg == nMsg && procId == 1 then
                  liftIO[GCounter[Int, ProcId], Int, IntRange, Unit](
                    (mvar.put(v.value))
                  )
                else pure(())
            } yield ()
          else pure(())
      } yield ()

    val stream = IntRange(0, nMsg + 1)

    val _ = ActorSystem(
      ActorMain.init[GCounter[Int, ProcId], Int, IntRange](
        GCounter.newGCounter[Int, ProcId]
      )(
        List.fill(10)(handle -> stream)
      ),
      "TestSystem",
      conf
    )

    val _ = assert(mvar.get() == 10 * Range(0, nMsg + 1).sum())
    println(s"\nTotal Error encounted: ${errorCount.get()}\n")
