# Testing

## Dependency

To use Akka TestKit add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-testkit-typed_$scala.binary_version$
  version=$akka.version$
  scope=test
}

@@@div { .group-scala }

We recommend using Akka TestKit with ScalaTest:

@@dependency[sbt,Maven,Gradle] {
  group=org.scalatest
  artifact=scalatest_$scala.binary_version$
  version=$scalatest.version$
  scope=test
}

@@@

## Introduction

Testing can either be done asynchronously using a real @apidoc[akka.actor.typed.ActorSystem] or synchronously on the testing thread using the
@apidoc[typed.*.BehaviorTestKit].

For testing logic in a @apidoc[Behavior] in isolation synchronous testing is preferred, but the features that can be
tested are limited. For testing interactions between multiple actors a more realistic asynchronous test is preferred.

## Asynchronous testing

Asynchronous testing uses a real @apidoc[akka.actor.typed.ActorSystem] that allows you to test your Actors in a more realistic environment.

The minimal setup consists of the test procedure, which provides the desired stimuli, the actor under test,
and an actor receiving replies. Bigger systems replace the actor under test with a network of actors, apply stimuli
at varying injection points and arrange results to be sent from different emission points, but the basic principle stays
the same in that a single procedure drives the test.

### Basic example

Actor under test:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #under-test }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #under-test }

Tests create an instance of @apidoc[ActorTestKit]. This provides access to:

* An ActorSystem
* Methods for spawning Actors. These are created under the root guardian
* A method to shut down the ActorSystem from the test suite

This first example is using the "raw" `ActorTestKit` but if you are using @scala[ScalaTest]@java[JUnit] you can
simplify the tests by using the @ref:[Test framework integration](#test-framework-integration). It's still good
to read this section to understand how it works.

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-header }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-header }

Your test is responsible for shutting down the @apidoc[akka.actor.typed.ActorSystem] e.g. using @scala[`BeforeAndAfterAll` when using ScalaTest]@java[`@AfterClass` when using JUnit].

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-shutdown }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-shutdown }

The following demonstrates:

* Creating an actor from the `TestKit`'s system using `spawn`
* Creating a `TestProbe`
* Verifying that the actor under test responds via the `TestProbe`

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-spawn }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-spawn }

Actors can also be spawned anonymously:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-spawn-anonymous }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-spawn-anonymous }

Note that you can add `import testKit._` to get access to the `spawn` and `createTestProbe` methods at the top level
without prefixing them with `testKit`.

#### Stopping actors
The method will wait until the actor stops or throw an assertion error in case of a timeout.

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-stop-actors }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-stop-actors }

The `stop` method can only be used for actors that were spawned by the same @apidoc[ActorTestKit]. Other actors
will not be stopped by that method.

### Observing mocked behavior

When testing a component (which may be an actor or not) that interacts with other actors it can be useful to not have to
run the other actors it depends on. Instead, you might want to create mock behaviors that accept and possibly respond to
messages in the same way the other actor would do but without executing any actual logic.
In addition to this it can also be useful to observe those interactions to assert that the component under test did send
the expected messages.
This allows the same kinds of tests as classic `TestActor`/`Autopilot`.

As an example, let's assume we'd like to test the following component:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #under-test-2 }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #under-test-2 }

In our test, we create a mocked `publisher` actor. Additionally we use `Behaviors.monitor` with a `TestProbe` in order
to be able to verify the interaction of the `producer` with the `publisher`:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-observe-mocked-behavior }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-observe-mocked-behavior }

### Test framework integration

@@@ div { .group-java }

If you are using JUnit you can use @javadoc[TestKitJunitResource](akka.actor.testkit.typed.javadsl.TestKitJunitResource) to have the async test kit automatically
shutdown when the test is complete.

Note that the dependency on JUnit is marked as optional from the test kit module, so your project must explicitly include
a dependency on JUnit to use this.

@@@

@@@ div { .group-scala }

If you are using ScalaTest you can extend @scaladoc[ScalaTestWithActorTestKit](akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit) to
have the async test kit automatically shutdown when the test is complete. This is done in `afterAll` from
the `BeforeAndAfterAll` trait. If you override that method you should call `super.afterAll` to shutdown the
test kit.

Note that the dependency on ScalaTest is marked as optional from the test kit module, so your project must explicitly include
a dependency on ScalaTest to use this.

@@@

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/ScalaTestIntegrationExampleSpec.scala) { #scalatest-integration }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/JunitIntegrationExampleTest.java) { #junit-integration }

### Configuration

By default the `ActorTestKit` loads configuration from `application-test.conf` if that exists, otherwise
it is using default configuration from the reference.conf resources that ship with the Akka libraries. The
application.conf of your project is not used in this case.
A specific configuration can be given as parameter when creating the TestKit.

If you prefer to use `application.conf` you can pass that as the configuration parameter to the TestKit.
It's loaded with:

Scala
:  @@snip [TestConfigExample.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/TestConfigExample.scala) { #default-application-conf }

Java
:  @@snip [TestConfigExample.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/TestConfigExample.java) { #default-application-conf }

It's often convenient to define configuration for a specific test as a `String` in the test itself and
use that as the configuration parameter to the TestKit. `ConfigFactory.parseString` can be used for that:

Scala
:  @@snip [TestConfigExample.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/TestConfigExample.scala) { #parse-string }

Java
:  @@snip [TestConfigExample.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/TestConfigExample.java) { #parse-string }

Combining those approaches using `withFallback`:

Scala
:  @@snip [TestConfigExample.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/TestConfigExample.scala) { #fallback-application-conf }

Java
:  @@snip [TestConfigExample.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/TestConfigExample.java) { #fallback-application-conf }


More information can be found in the [documentation of the configuration library](https://github.com/lightbend/config#using-the-library).

@@@ note

Note that `reference.conf` files are intended for libraries to define default values and shouldn't be used
in an application. It's not supported to override a config property owned by one library in a `reference.conf`
of another library.

@@@

### Controlling the scheduler

It can be hard to reliably unit test specific scenario's when your actor relies on timing:
especially when running many tests in parallel it can be hard to get the timing just right.
Making such tests more reliable by using generous timeouts make the tests take a long time to run.

For such situations, we provide a scheduler where you can manually, explicitly advance the clock.

Scala
:   @@snip [ManualTimerExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/ManualTimerExampleSpec.scala) { #manual-scheduling-simple }

Java
:   @@snip [ManualTimerExampleTest.scala](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/ManualTimerExampleTest.java) { #manual-scheduling-simple }


## Synchronous behavior testing

The `BehaviorTestKit` provides a very nice way of unit testing a `Behavior` in a deterministic way, but it has
some limitations to be aware of.

Certain @apidoc[Behavior]s will be hard to test synchronously and the `BehaviorTestKit` doesn't support testing of
all features. In those cases the @ref:[asynchronous ActorTestKit](#asynchronous-testing) is recommended. Example of
limitations:

* Spawning of @scala[`Future`]@java[`CompletionStage`] or other asynchronous task and you rely on a callback to
  complete before observing the effect you want to test.
* Usage of scheduler or timers not supported.
* `EventSourcedBehavior` can't be tested.
* Interactions with other actors must be stubbed.
* Blackbox testing style.

The `BehaviorTestKit` will be improved and some of these problems will be removed but it will always have limitations.

The following demonstrates how to test:

* Spawning child actors
* Spawning child actors anonymously
* Sending a message either as a reply or to another actor
* Sending a message to a child actor

The examples below require the following imports:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #imports }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #imports }

Each of the tests are testing an actor that based on the message executes a different effect to be tested:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #under-test }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #under-test }

For creating a child actor a noop actor is created:


Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #child }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #child }

All of the tests make use of the @apidoc[BehaviorTestKit] to avoid the need for a real `ActorContext`. Some of the tests
make use of the @apidoc[TestInbox] which allows the creation of an @apidoc[akka.actor.typed.ActorRef] that can be used for synchronous testing, similar to the
`TestProbe` used for asynchronous testing.


### Spawning children

With a name:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child }

Anonymously:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-anonymous-child }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-anonymous-child }

### Sending messages

For testing sending a message a @apidoc[TestInbox] is created that provides an @apidoc[akka.actor.typed.ActorRef] and methods to assert against the
messages that have been sent to it.

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-message }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-message }

Another use case is sending a message to a child actor you can do this by looking up the @apidoc[TestInbox] for
a child actor from the @apidoc[BehaviorTestKit]:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child-message }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child-message }

For anonymous children the actor names are generated in a deterministic way:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child-message-anonymous }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child-message-anonymous }

### Testing other effects

The @apidoc[BehaviorTestKit] keeps track other effects you can verify, look at the sub-classes of @apidoc[akka.actor.testkit.typed.Effect]

 * SpawnedAdapter
 * Stopped
 * Watched
 * WatchedWith
 * Unwatched
 * Scheduled

### Checking for Log Messages

The @apidoc[BehaviorTestKit] also keeps track of everything that is being logged. Here, you can see an example on how to check
if the behavior logged certain messages:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-check-logging }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-check-logging }


See the other public methods and API documentation on @apidoc[BehaviorTestKit] for other types of verification.

