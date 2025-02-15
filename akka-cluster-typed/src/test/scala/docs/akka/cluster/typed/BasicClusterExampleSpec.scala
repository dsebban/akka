/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.testkit.SocketUtil
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
//#cluster-imports
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.cluster.typed._
//#cluster-imports
import akka.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.duration._

object BasicClusterExampleSpec {
  val configSystem1 = ConfigFactory.parseString(s"""
#config-seeds
akka {
  actor {
    provider = "cluster"
  }
  remote.artery {
    canonical {
      hostname = "127.0.0.1"
      port = 2551
    }
  }

  cluster {
    seed-nodes = [
      "akka://ClusterSystem@127.0.0.1:2551",
      "akka://ClusterSystem@127.0.0.1:2552"]
  }
}
#config-seeds
     """)

  val configSystem2 = ConfigFactory.parseString(s"""
        akka.remote.classic.netty.tcp.port = 0
        akka.remote.artery.canonical.port = 0
     """).withFallback(configSystem1)
}

class BasicClusterConfigSpec extends WordSpec with ScalaFutures with Eventually with Matchers with LogCapturing {
  import BasicClusterExampleSpec._

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(100, Millis)))

  "Cluster API" must {
    "init cluster" in {
      // config is pulled into docs, but we don't want to hardcode ports because that makes for brittle tests
      val sys1Port = SocketUtil.temporaryLocalPort()
      val sys2Port = SocketUtil.temporaryLocalPort()
      def config(port: Int) = ConfigFactory.parseString(s"""
          akka.remote.classic.netty.tcp.port = $port
          akka.cluster.seed-nodes = [ "akka://ClusterSystem@127.0.0.1:$sys1Port", "akka://ClusterSystem@127.0.0.1:$sys2Port" ]
        """)

      val system1 = ActorSystem[Nothing](Behaviors.empty, "ClusterSystem", config(sys1Port).withFallback(configSystem1))
      val system2 = ActorSystem[Nothing](Behaviors.empty, "ClusterSystem", config(sys2Port).withFallback(configSystem2))
      try {
        Cluster(system1)
        Cluster(system2)
      } finally {
        system1.terminate()
        system1.whenTerminated.futureValue
        system2.terminate()
        system2.whenTerminated.futureValue
      }

    }
  }
}

object BasicClusterManualSpec {
  val clusterConfig = ConfigFactory.parseString(s"""
#config
akka {
  actor.provider = "cluster"
  remote.artery {
    canonical {
      hostname = "127.0.0.1"
      port = 2551
    }
  }
}
#config
     """)

  val noPort = ConfigFactory.parseString("""
      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
    """)

}

class BasicClusterManualSpec extends WordSpec with ScalaFutures with Eventually with Matchers with LogCapturing {

  import BasicClusterManualSpec._

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(100, Millis)))

  "Cluster API" must {
    "init cluster" in {

      val system = ActorSystem[Nothing](Behaviors.empty, "ClusterSystem", noPort.withFallback(clusterConfig))
      val system2 = ActorSystem[Nothing](Behaviors.empty, "ClusterSystem", noPort.withFallback(clusterConfig))

      try {
        //#cluster-create
        val cluster = Cluster(system)
        //#cluster-create
        val cluster2 = Cluster(system2)

        //#cluster-join
        cluster.manager ! Join(cluster.selfMember.address)
        //#cluster-join
        cluster2.manager ! Join(cluster.selfMember.address)

        eventually {
          cluster.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
          cluster2.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
        }

        //#cluster-leave
        cluster2.manager ! Leave(cluster2.selfMember.address)
        //#cluster-leave

        eventually {
          cluster.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up)
          cluster2.isTerminated shouldEqual true
        }
      } finally {
        system.terminate()
        system.whenTerminated.futureValue
        system2.terminate()
        system2.whenTerminated.futureValue
      }
    }

    "subscribe to cluster events" in {
      implicit val system1 = ActorSystem[Nothing](Behaviors.empty, "ClusterSystem", noPort.withFallback(clusterConfig))
      val system2 = ActorSystem[Nothing](Behaviors.empty, "ClusterSystem", noPort.withFallback(clusterConfig))
      val system3 = ActorSystem[Nothing](Behaviors.empty, "ClusterSystem", noPort.withFallback(clusterConfig))

      try {
        val cluster1 = Cluster(system1)
        val cluster2 = Cluster(system2)
        val cluster3 = Cluster(system3)
        def cluster = cluster1

        val probe1 = TestProbe[MemberEvent]()(system1)
        val subscriber = probe1.ref
        //#cluster-subscribe
        cluster.subscriptions ! Subscribe(subscriber, classOf[MemberEvent])
        //#cluster-subscribe

        cluster1.manager ! Join(cluster1.selfMember.address)
        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up)
        }
        probe1.expectMessage(MemberUp(cluster1.selfMember))

        cluster2.manager ! Join(cluster1.selfMember.address)
        probe1.within(10.seconds) {
          probe1.expectMessageType[MemberJoined].member.address shouldEqual cluster2.selfMember.address
          probe1.expectMessageType[MemberUp].member.address shouldEqual cluster2.selfMember.address
        }
        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
          cluster2.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
        }

        cluster3.manager ! Join(cluster1.selfMember.address)
        probe1.within(10.seconds) {
          probe1.expectMessageType[MemberJoined].member.address shouldEqual cluster3.selfMember.address
          probe1.expectMessageType[MemberUp].member.address shouldEqual cluster3.selfMember.address
        }
        eventually {
          cluster1.state.members.toList
            .map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up, MemberStatus.up)
          cluster2.state.members.toList
            .map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up, MemberStatus.up)
          cluster3.state.members.toList
            .map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up, MemberStatus.up)
        }

        val anotherMemberAddress = cluster2.selfMember.address
        //#cluster-leave-example
        cluster.manager ! Leave(anotherMemberAddress)
        // subscriber will receive events MemberLeft, MemberExited and MemberRemoved
        //#cluster-leave-example
        probe1.within(10.seconds) {
          probe1.expectMessageType[MemberLeft].member.address shouldEqual cluster2.selfMember.address
          probe1.expectMessageType[MemberExited].member.address shouldEqual cluster2.selfMember.address
          probe1.expectMessageType[MemberRemoved].member.address shouldEqual cluster2.selfMember.address
        }

        eventually {
          cluster1.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
          cluster3.state.members.toList.map(_.status) shouldEqual List(MemberStatus.up, MemberStatus.up)
        }

        eventually {
          cluster2.isTerminated should ===(true)
        }
        // via coordinated shutdown
        system2.whenTerminated.futureValue

        system1.log.info("Downing node 3")
        cluster1.manager ! Down(cluster3.selfMember.address)
        probe1.expectMessageType[MemberDowned].member.address shouldEqual cluster3.selfMember.address
        probe1.expectMessageType[MemberRemoved](10.seconds).member.address shouldEqual cluster3.selfMember.address

        probe1.expectNoMessage()

        // via coordinated shutdown
        system3.whenTerminated.futureValue

      } finally {
        system1.terminate()
        system1.whenTerminated.futureValue
        system2.terminate()
        system2.whenTerminated.futureValue
        system3.terminate()
        system3.whenTerminated.futureValue
      }
    }
  }
}
