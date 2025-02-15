/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.Optional

import akka.event.Logging

import scala.annotation.tailrec
import scala.reflect.{ classTag, ClassTag }
import akka.japi.function
import java.net.URLEncoder
import java.time.Duration

import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.stream.impl.TraversalBuilder
import akka.util.JavaDurationConverters._

import scala.compat.java8.OptionConverters._
import akka.util.{ ByteString, OptionVal }

import scala.concurrent.duration.FiniteDuration

/**
 * Holds attributes which can be used to alter [[akka.stream.scaladsl.Flow]] / [[akka.stream.javadsl.Flow]]
 * or [[akka.stream.scaladsl.GraphDSL]] / [[akka.stream.javadsl.GraphDSL]] materialization.
 *
 * Note that more attributes for the [[Materializer]] are defined in [[ActorAttributes]].
 *
 * The ``attributeList`` is ordered with the most specific attribute first, least specific last.
 * Note that the order was the opposite in Akka 2.4.x.
 *
 * Operators should in general not access the `attributeList` but instead use `get` to get the expected
 * value of an attribute.
 */
final case class Attributes(attributeList: List[Attributes.Attribute] = Nil) {

  import Attributes._

  /**
   * Note that this must only be used during traversal building and not during materialization
   * as it will then always return true because of the defaults from the ActorMaterializerSettings
   * INTERNAL API
   */
  private[stream] def isAsync: Boolean = {
    attributeList.nonEmpty && attributeList.exists {
      case AsyncBoundary                 => true
      case ActorAttributes.Dispatcher(_) => true
      case _                             => false
    }
  }

  /**
   * Java API: Get the most specific attribute value for a given Attribute type or subclass thereof.
   * If no such attribute exists, return a `default` value.
   *
   * The most specific value is the value that was added closest to the graph or operator itself or if
   * the same attribute was added multiple times to the same graph, the last to be added.
   *
   * This is the expected way for operators to access attributes.
   */
  def getAttribute[T <: Attribute](c: Class[T], default: T): T =
    getAttribute(c).orElse(default)

  /**
   * Java API: Get the most specific attribute value for a given Attribute type or subclass thereof.
   *
   * The most specific value is the value that was added closest to the graph or operator itself or if
   * the same attribute was added multiple times to the same graph, the last to be added.
   *
   * This is the expected way for operators to access attributes.
   */
  def getAttribute[T <: Attribute](c: Class[T]): Optional[T] =
    attributeList.collectFirst { case attr if c.isInstance(attr) => c.cast(attr) }.asJava

  /**
   * Scala API: Get the most specific attribute value for a given Attribute type or subclass thereof or
   * if no such attribute exists, return a default value.
   *
   * The most specific value is the value that was added closest to the graph or operator itself or if
   * the same attribute was added multiple times to the same graph, the last to be added.
   *
   * This is the expected way for operators to access attributes.
   */
  def get[T <: Attribute: ClassTag](default: T): T =
    get[T] match {
      case Some(a) => a
      case None    => default
    }

  /**
   * Scala API: Get the most specific attribute value for a given Attribute type or subclass thereof.
   *
   * The most specific value is the value that was added closest to the graph or operator itself or if
   * the same attribute was added multiple times to the same graph, the last to be added.
   *
   * This is the expected way for operators to access attributes.
   *
   * @see [[Attributes#get()]] For providing a default value if the attribute was not set
   */
  def get[T <: Attribute: ClassTag]: Option[T] = {
    val c = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    attributeList.collectFirst { case attr if c.isInstance(attr) => c.cast(attr) }
  }

  /**
   * Scala API: Get the most specific of one of the mandatory attributes. Mandatory attributes are guaranteed
   * to always be among the attributes when the attributes are coming from a materialization.
   */
  def mandatoryAttribute[T <: MandatoryAttribute: ClassTag]: T = {
    val c = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    getMandatoryAttribute(c)
  }

  /**
   * Java API: Get the most specific of one of the mandatory attributes. Mandatory attributes are guaranteed
   * to always be among the attributes when the attributes are coming from a materialization.
   *
   * @param c A class that is a subtype of [[MandatoryAttribute]]
   */
  def getMandatoryAttribute[T <: MandatoryAttribute](c: Class[T]): T = {
    @tailrec
    def find(list: List[Attribute]): OptionVal[Attribute] = list match {
      case Nil => OptionVal.None
      case head :: tail =>
        if (c.isInstance(head)) OptionVal.Some(head)
        else find(tail)
    }

    find(attributeList) match {
      case OptionVal.Some(t) => t.asInstanceOf[T]
      case OptionVal.None    => throw new IllegalStateException(s"Mandatory attribute [$c] not found")
    }
  }

  /**
   * Adds given attributes. Added attributes are considered more specific than
   * already existing attributes of the same type.
   */
  def and(other: Attributes): Attributes = {
    if (attributeList.isEmpty) other
    else if (other.attributeList.isEmpty) this
    else if (other.attributeList.tail.isEmpty) Attributes(other.attributeList.head :: attributeList)
    else Attributes(other.attributeList ::: attributeList)
  }

  /**
   * Adds given attribute. Added attribute is considered more specific than
   * already existing attributes of the same type.
   */
  def and(other: Attribute): Attributes =
    Attributes(other :: attributeList)

  /**
   * Extracts Name attributes and concatenates them.
   */
  def nameLifted: Option[String] = {
    @tailrec def concatNames(i: Iterator[Attribute], first: String, buf: java.lang.StringBuilder): String =
      if (i.hasNext)
        i.next() match {
          case Name(n) =>
            if (buf ne null) concatNames(i, null, buf.append('-').append(n))
            else if (first ne null) {
              val b = new java.lang.StringBuilder((first.length + n.length) * 2)
              concatNames(i, null, b.append(first).append('-').append(n))
            } else concatNames(i, n, null)
          case _ => concatNames(i, first, buf)
        } else if (buf eq null) first
      else buf.toString

    Option(concatNames(attributeList.reverseIterator, null, null))
  }

  /**
   * INTERNAL API
   */
  @InternalApi def nameOrDefault(default: String = "unnamed"): String = {
    @tailrec def find(attrs: List[Attribute]): String = attrs match {
      case Attributes.Name(name) :: _ => name
      case _ :: tail                  => find(tail)
      case Nil                        => default
    }
    find(attributeList)
  }

  /**
   * Test whether the given attribute is contained within this attributes list.
   *
   * Note that operators in general should not inspect the whole hierarchy but instead use
   * `get` to get the most specific attribute value.
   */
  def contains(attr: Attribute): Boolean = attributeList.contains(attr)

  /**
   * Java API
   *
   * The list is ordered with the most specific attribute first, least specific last.
   * Note that the order was the opposite in Akka 2.4.x.
   *
   * Note that operators in general should not inspect the whole hierarchy but instead use
   * `get` to get the most specific attribute value.
   */
  def getAttributeList(): java.util.List[Attribute] = {
    import akka.util.ccompat.JavaConverters._
    attributeList.asJava
  }

  /**
   * Java API: Get all attributes of a given `Class` or
   * subclass thereof.
   *
   * The list is ordered with the most specific attribute first, least specific last.
   * Note that the order was the opposite in Akka 2.4.x.
   *
   * Note that operators in general should not inspect the whole hierarchy but instead use
   * `get` to get the most specific attribute value.
   */
  def getAttributeList[T <: Attribute](c: Class[T]): java.util.List[T] =
    if (attributeList.isEmpty) java.util.Collections.emptyList()
    else {
      val result = new java.util.ArrayList[T]
      attributeList.foreach { a =>
        if (c.isInstance(a))
          result.add(c.cast(a))
      }
      result
    }

  /**
   * Scala API: Get all attributes of a given type (or subtypes thereof).
   *
   * Note that operators in general should not inspect the whole hierarchy but instead use
   * `get` to get the most specific attribute value.
   *
   * The list is ordered with the most specific attribute first, least specific last.
   * Note that the order was the opposite in Akka 2.4.x.
   */
  def filtered[T <: Attribute: ClassTag]: List[T] = {
    val c = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
    attributeList.collect { case attr if c.isAssignableFrom(attr.getClass) => c.cast(attr) }
  }

  /**
   * Java API: Get the least specific attribute (added first) of a given `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  @deprecated("Attributes should always be most specific, use getAttribute[T]", "2.5.7")
  def getFirstAttribute[T <: Attribute](c: Class[T], default: T): T =
    getFirstAttribute(c).orElse(default)

  /**
   * Java API: Get the least specific attribute (added first) of a given `Class` or subclass thereof.
   */
  @deprecated("Attributes should always be most specific, use get[T]", "2.5.7")
  def getFirstAttribute[T <: Attribute](c: Class[T]): Optional[T] =
    attributeList.reverseIterator.collectFirst { case attr if c.isInstance(attr) => c.cast(attr) }.asJava

  /**
   * Scala API: Get the least specific attribute (added first) of a given type parameter T `Class` or subclass thereof.
   * If no such attribute exists the `default` value is returned.
   */
  @deprecated("Attributes should always be most specific, use get[T]", "2.5.7")
  def getFirst[T <: Attribute: ClassTag](default: T): T = {
    getFirst[T] match {
      case Some(a) => a
      case None    => default
    }
  }

  /**
   * Scala API: Get the least specific attribute (added first) of a given type parameter T `Class` or subclass thereof.
   */
  @deprecated("Attributes should always be most specific, use get[T]", "2.5.7")
  def getFirst[T <: Attribute: ClassTag]: Option[T] = {
    val c = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    attributeList.reverseIterator.collectFirst { case attr if c.isInstance(attr) => c.cast(attr) }
  }

}

/**
 * Note that more attributes for the [[Materializer]] are defined in [[ActorAttributes]].
 */
object Attributes {

  trait Attribute

  /**
   * Attributes that are always present (is defined with default values by the materializer)
   *
   * Not for user extension
   */
  @DoNotInherit
  sealed trait MandatoryAttribute extends Attribute

  final case class Name(n: String) extends Attribute

  /**
   * Each asynchronous piece of a materialized stream topology is executed by one Actor
   * that manages an input buffer for all inlets of its shape. This attribute configures
   * the initial and maximal input buffer in number of elements for each inlet.
   *
   * Use factory method [[Attributes#inputBuffer]] to create instances.
   */
  final case class InputBuffer(initial: Int, max: Int) extends MandatoryAttribute

  final case class LogLevels(onElement: Logging.LogLevel, onFinish: Logging.LogLevel, onFailure: Logging.LogLevel)
      extends Attribute
  final case object AsyncBoundary extends Attribute

  object LogLevels {

    /** Use to disable logging on certain operations when configuring [[Attributes#logLevels]] */
    final val Off: Logging.LogLevel = Logging.levelFor("off").get

    /** Use to enable logging at ERROR level for certain operations when configuring [[Attributes#logLevels]] */
    final val Error: Logging.LogLevel = Logging.ErrorLevel

    /** Use to enable logging at WARNING level for certain operations when configuring [[Attributes#logLevels]] */
    final val Warning: Logging.LogLevel = Logging.WarningLevel

    /** Use to enable logging at INFO level for certain operations when configuring [[Attributes#logLevels]] */
    final val Info: Logging.LogLevel = Logging.InfoLevel

    /** Use to enable logging at DEBUG level for certain operations when configuring [[Attributes#logLevels]] */
    final val Debug: Logging.LogLevel = Logging.DebugLevel
  }

  /** Java API: Use to disable logging on certain operations when configuring [[Attributes#createLogLevels]] */
  def logLevelOff: Logging.LogLevel = LogLevels.Off

  /** Use to enable logging at ERROR level for certain operations when configuring [[Attributes#createLogLevels]] */
  def logLevelError: Logging.LogLevel = LogLevels.Error

  /** Use to enable logging at WARNING level for certain operations when configuring [[Attributes#createLogLevels]] */
  def logLevelWarning: Logging.LogLevel = LogLevels.Warning

  /** Use to enable logging at INFO level for certain operations when configuring [[Attributes#createLogLevels]] */
  def logLevelInfo: Logging.LogLevel = LogLevels.Info

  /** Use to enable logging at DEBUG level for certain operations when configuring [[Attributes#createLogLevels]] */
  def logLevelDebug: Logging.LogLevel = LogLevels.Debug

  /**
   * INTERNAL API
   */
  def apply(attribute: Attribute): Attributes =
    apply(attribute :: Nil)

  val none: Attributes = Attributes()

  val asyncBoundary: Attributes = Attributes(AsyncBoundary)

  /**
   * Specifies the name of the operation.
   * If the name is null or empty the name is ignored, i.e. [[#none]] is returned.
   *
   * When using this method the name is encoded with URLEncoder with UTF-8 because
   * the name is sometimes used as part of actor name. If that is not desired
   * the name can be added in it's raw format using `.addAttributes(Attributes(Name(name)))`.
   */
  def name(name: String): Attributes =
    if (name == null || name.isEmpty) none
    else Attributes(Name(URLEncoder.encode(name, ByteString.UTF_8)))

  /**
   * Each asynchronous piece of a materialized stream topology is executed by one Actor
   * that manages an input buffer for all inlets of its shape. This attribute configures
   * the initial and maximal input buffer in number of elements for each inlet.
   */
  def inputBuffer(initial: Int, max: Int): Attributes = Attributes(InputBuffer(initial, max))

  /**
   * Java API
   *
   * Configures `log()` operator log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[Attributes#logLevelOff]].
   *
   */
  def createLogLevels(
      onElement: Logging.LogLevel,
      onFinish: Logging.LogLevel,
      onFailure: Logging.LogLevel): Attributes =
    logLevels(onElement, onFinish, onFailure)

  /**
   * Java API
   *
   * Configures `log()` operator log-levels to be used when logging onElement.
   * Logging a certain operation can be completely disabled by using [[Attributes#logLevelOff]].
   *
   */
  def createLogLevels(onElement: Logging.LogLevel): Attributes =
    logLevels(onElement)

  /**
   * Configures `log()` operator log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[LogLevels.Off]].
   *
   * See [[Attributes.createLogLevels]] for Java API
   */
  def logLevels(
      onElement: Logging.LogLevel = Logging.DebugLevel,
      onFinish: Logging.LogLevel = Logging.DebugLevel,
      onFailure: Logging.LogLevel = Logging.ErrorLevel) =
    Attributes(LogLevels(onElement, onFinish, onFailure))

  /**
   * Compute a name by concatenating all Name attributes that the given module
   * has, returning the given default value if none are found.
   */
  def extractName(builder: TraversalBuilder, default: String): String = {
    builder.attributes.nameOrDefault(default)
  }
}

/**
 * Attributes for the [[Materializer]].
 * Note that more attributes defined in [[Attributes]].
 */
object ActorAttributes {
  import Attributes._

  /**
   * Configures the dispatcher to be used by streams.
   *
   * Use factory method [[ActorAttributes#dispatcher]] to create instances.
   */
  final case class Dispatcher(dispatcher: String) extends MandatoryAttribute

  final case class SupervisionStrategy(decider: Supervision.Decider) extends MandatoryAttribute

  val IODispatcher: Dispatcher = ActorAttributes.Dispatcher("akka.stream.materializer.blocking-io-dispatcher")

  /**
   * Specifies the name of the dispatcher. This also adds an async boundary.
   */
  def dispatcher(dispatcher: String): Attributes = Attributes(Dispatcher(dispatcher))

  /**
   * Scala API: Decides how exceptions from user are to be handled.
   *
   * Operators supporting supervision strategies explicitly document that they do so. If a operator does not document
   * support for these, it should be assumed it does not support supervision.
   *
   * For the Java API see [[#withSupervisionStrategy]]
   */
  def supervisionStrategy(decider: Supervision.Decider): Attributes =
    Attributes(SupervisionStrategy(decider))

  /**
   * Java API: Decides how exceptions from application code are to be handled.
   *
   * Operators supporting supervision strategies explicitly document that they do so. If a operator does not document
   * support for these, it should be assumed it does not support supervision.
   *
   * For the Scala API see [[#supervisionStrategy]]
   */
  def withSupervisionStrategy(decider: function.Function[Throwable, Supervision.Directive]): Attributes =
    ActorAttributes.supervisionStrategy(decider.apply)

  /**
   * Java API
   *
   * Configures `log()` operator log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[Attributes#logLevelOff]].
   *
   */
  def createLogLevels(
      onElement: Logging.LogLevel,
      onFinish: Logging.LogLevel,
      onFailure: Logging.LogLevel): Attributes =
    logLevels(onElement, onFinish, onFailure)

  /**
   * Java API
   *
   * Configures `log()` operator log-levels to be used when logging onElement.
   * Logging a certain operation can be completely disabled by using [[Attributes#logLevelOff]].
   *
   */
  def createLogLevels(onElement: Logging.LogLevel): Attributes =
    logLevels(onElement)

  /**
   * Configures `log()` operator log-levels to be used when logging.
   * Logging a certain operation can be completely disabled by using [[LogLevels.Off]].
   *
   * See [[Attributes.createLogLevels]] for Java API
   */
  def logLevels(
      onElement: Logging.LogLevel = Logging.DebugLevel,
      onFinish: Logging.LogLevel = Logging.DebugLevel,
      onFailure: Logging.LogLevel = Logging.ErrorLevel) =
    Attributes(LogLevels(onElement, onFinish, onFailure))

  /**
   * Enables additional low level troubleshooting logging at DEBUG log level
   *
   * Use factory method [[#debugLogging]] to create.
   */
  final case class DebugLogging(enabled: Boolean) extends MandatoryAttribute

  /**
   * Enables additional low level troubleshooting logging at DEBUG log level
   */
  def debugLogging(enabled: Boolean): Attributes =
    Attributes(DebugLogging(enabled))

  /**
   * Defines a timeout for stream subscription and what action to take when that hits.
   *
   * Use factory method `streamSubscriptionTimeout` to create.
   */
  final case class StreamSubscriptionTimeout(timeout: FiniteDuration, mode: StreamSubscriptionTimeoutTerminationMode)
      extends MandatoryAttribute

  /**
   * Scala API: Defines a timeout for stream subscription and what action to take when that hits.
   */
  def streamSubscriptionTimeout(timeout: FiniteDuration, mode: StreamSubscriptionTimeoutTerminationMode): Attributes =
    Attributes(StreamSubscriptionTimeout(timeout, mode))

  /**
   * Java API: Defines a timeout for stream subscription and what action to take when that hits.
   */
  def streamSubscriptionTimeout(timeout: Duration, mode: StreamSubscriptionTimeoutTerminationMode): Attributes =
    streamSubscriptionTimeout(timeout.asScala, mode)

  /**
   * Maximum number of elements emitted in batch if downstream signals large demand.
   *
   * Use factory method [[#outputBurstLimit]] to create.
   */
  final case class OutputBurstLimit(limit: Int) extends MandatoryAttribute

  /**
   * Maximum number of elements emitted in batch if downstream signals large demand.
   */
  def outputBurstLimit(limit: Int): Attributes =
    Attributes(OutputBurstLimit(limit))

  /**
   * Test utility: fuzzing mode means that GraphStage events are not processed
   * in FIFO order within a fused subgraph, but randomized.
   *
   * Use factory method [[#fuzzingMode]] to create.
   */
  final case class FuzzingMode(enabled: Boolean) extends MandatoryAttribute

  /**
   * Test utility: fuzzing mode means that GraphStage events are not processed
   * in FIFO order within a fused subgraph, but randomized.
   */
  def fuzzingMode(enabled: Boolean): Attributes =
    Attributes(FuzzingMode(enabled))

  /**
   * Configure the maximum buffer size for which a FixedSizeBuffer will be preallocated.
   * This defaults to a large value because it is usually better to fail early when
   * system memory is not sufficient to hold the buffer.
   *
   * Use factory method [[#maxFixedBufferSize]] to create.
   */
  final case class MaxFixedBufferSize(size: Int) extends MandatoryAttribute

  /**
   * Configure the maximum buffer size for which a FixedSizeBuffer will be preallocated.
   * This defaults to a large value because it is usually better to fail early when
   * system memory is not sufficient to hold the buffer.
   */
  def maxFixedBufferSize(size: Int): Attributes =
    Attributes(MaxFixedBufferSize(size: Int))

  /**
   * Limit for number of messages that can be processed synchronously in stream to substream communication.
   *
   * Use factory method [[#syncProcessingLimit]] to create.
   */
  final case class SyncProcessingLimit(limit: Int) extends MandatoryAttribute

  /**
   * Limit for number of messages that can be processed synchronously in stream to substream communication
   */
  def syncProcessingLimit(limit: Int): Attributes =
    Attributes(SyncProcessingLimit(limit))

  /**
   * FIXME Is this really needed anymore now that we have indirect dispatcher config?
   */
  final case class BlockingIoDispatcher(dispatcher: String) extends MandatoryAttribute
  def blockingIoDispatcher(dispatcher: String): Attributes =
    Attributes(BlockingIoDispatcher(dispatcher))
}

/**
 * Attributes for stream refs ([[akka.stream.SourceRef]] and [[akka.stream.SinkRef]]).
 * Note that more attributes defined in [[Attributes]] and [[ActorAttributes]].
 */
object StreamRefAttributes {
  import Attributes._

  /** Attributes specific to stream refs.
   *
   * Not for user extension.
   */
  @DoNotInherit
  sealed trait StreamRefAttribute extends Attribute

  final case class SubscriptionTimeout(timeout: FiniteDuration) extends StreamRefAttribute
  final case class BufferCapacity(capacity: Int) extends StreamRefAttribute {
    require(capacity > 0, "Buffer capacity must be > 0")
  }
  final case class DemandRedeliveryInterval(timeout: FiniteDuration) extends StreamRefAttribute
  final case class FinalTerminationSignalDeadline(timeout: FiniteDuration) extends StreamRefAttribute

  /**
   * Scala API: Specifies the subscription timeout within which the remote side MUST subscribe to the handed out stream reference.
   */
  def subscriptionTimeout(timeout: FiniteDuration): Attributes = Attributes(SubscriptionTimeout(timeout))

  /**
   * Java API: Specifies the subscription timeout within which the remote side MUST subscribe to the handed out stream reference.
   */
  def subscriptionTimeout(timeout: Duration): Attributes = subscriptionTimeout(timeout.asScala)

  /**
   * Specifies the size of the buffer on the receiving side that is eagerly filled even without demand.
   */
  def bufferCapacity(capacity: Int): Attributes = Attributes(BufferCapacity(capacity))

  /**
   *  Scala API: If no new elements arrive within this timeout, demand is redelivered.
   */
  def demandRedeliveryInterval(timeout: FiniteDuration): Attributes =
    Attributes(DemandRedeliveryInterval(timeout))

  /**
   *  Java API: If no new elements arrive within this timeout, demand is redelivered.
   */
  def demandRedeliveryInterval(timeout: Duration): Attributes =
    demandRedeliveryInterval(timeout.asScala)

  /**
   * Scala API: The time between the Terminated signal being received and when the local SourceRef determines to fail itself
   */
  def finalTerminationSignalDeadline(timeout: FiniteDuration): Attributes =
    Attributes(FinalTerminationSignalDeadline(timeout))

  /**
   * Java API: The time between the Terminated signal being received and when the local SourceRef determines to fail itself
   */
  def finalTerminationSignalDeadline(timeout: Duration): Attributes =
    finalTerminationSignalDeadline(timeout.asScala)

}
