/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.typed;

// #cluster-imports
import akka.actor.Address;
import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.cluster.ClusterEvent;
import akka.cluster.typed.*;
// #cluster-imports
import akka.actor.testkit.typed.javadsl.TestProbe;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

// FIXME use awaitAssert to await cluster forming like in BasicClusterExampleSpec
public class BasicClusterExampleTest { // extends JUnitSuite {

  private Config clusterConfig =
      ConfigFactory.parseString(
          "akka { \n"
              + "  actor.provider = cluster \n"
              + "  remote.artery { \n"
              + "    canonical { \n"
              + "      hostname = \"127.0.0.1\" \n"
              + "      port = 2551 \n"
              + "    } \n"
              + "  } \n"
              + "}  \n");

  private Config noPort =
      ConfigFactory.parseString(
          "      akka.remote.classic.netty.tcp.port = 0 \n"
              + "      akka.remote.artery.canonical.port = 0 \n");

  // @Test
  public void clusterApiExample() {
    ActorSystem<Object> system =
        ActorSystem.create(Behaviors.empty(), "ClusterSystem", noPort.withFallback(clusterConfig));
    ActorSystem<Object> system2 =
        ActorSystem.create(Behaviors.empty(), "ClusterSystem", noPort.withFallback(clusterConfig));

    try {
      // #cluster-create
      Cluster cluster = Cluster.get(system);
      // #cluster-create
      Cluster cluster2 = Cluster.get(system2);

      // #cluster-join
      cluster.manager().tell(Join.create(cluster.selfMember().address()));
      // #cluster-join

      cluster2.manager().tell(Join.create(cluster.selfMember().address()));

      // TODO wait for/verify cluster to form

      // #cluster-leave
      cluster2.manager().tell(Leave.create(cluster2.selfMember().address()));
      // #cluster-leave

      // TODO wait for/verify node 2 leaving

    } finally {
      system.terminate();
      system2.terminate();
    }
  }

  // @Test
  public void clusterLeave() throws Exception {
    ActorSystem<Object> system =
        ActorSystem.create(Behaviors.empty(), "ClusterSystem", noPort.withFallback(clusterConfig));
    ActorSystem<Object> system2 =
        ActorSystem.create(Behaviors.empty(), "ClusterSystem", noPort.withFallback(clusterConfig));

    try {
      Cluster cluster = Cluster.get(system);
      Cluster cluster2 = Cluster.get(system2);

      TestProbe<ClusterEvent.MemberEvent> testProbe = TestProbe.create(system);
      ActorRef<ClusterEvent.MemberEvent> subscriber = testProbe.getRef();
      // #cluster-subscribe
      cluster.subscriptions().tell(Subscribe.create(subscriber, ClusterEvent.MemberEvent.class));
      // #cluster-subscribe

      Address anotherMemberAddress = cluster2.selfMember().address();
      // #cluster-leave-example
      cluster.manager().tell(Leave.create(anotherMemberAddress));
      // subscriber will receive events MemberLeft, MemberExited and MemberRemoved
      // #cluster-leave-example
      testProbe.expectMessageClass(ClusterEvent.MemberLeft.class);
      testProbe.expectMessageClass(ClusterEvent.MemberExited.class);
      testProbe.expectMessageClass(ClusterEvent.MemberRemoved.class);

    } finally {
      system.terminate();
      system2.terminate();
    }
  }
}
