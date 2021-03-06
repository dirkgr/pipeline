package org.allenai.pipeline

import org.allenai.common.{ Logging, Timing }

import scala.concurrent.duration.Duration

/** An individual step in a data processing pipeline.
  * A lazily evaluated calculation, with support for in-memory caching and persistence.
  *
  * @tparam  T  the type of data being produced
  */
trait Producer[T] extends PipelineStep with CachingEnabled with Logging {
  self =>
  /** Produces the data, if not already produced and cached. */
  protected def create: T

  /** Describes the persistence location of the data produced.
    *
    * The user code should provide the className, classVersion, parameters and dependencies.
    * Usually these are provided via one of the convenience mixins: Ai2StepInfo, Ai2SparkStepInfo.
    *
    * When persisted via Pipeline.persist, the fileid is determined as
    * s"${stepInfo.className}.${stepInfo.signature.id}. The fileid may mean different things
    * in different contexts, for example, if T is an RDD, many files may be persisted under the
    * fileid/.* path.
    */
  override def stepInfo: PipelineStepInfo

  /** Return the computed value. */
  def get: T = {
    val className = stepInfo.className
    val returnValue =
      if (!cachingEnabled) {
        logger.debug(s"$className caching disabled, recomputing")
        createAndTime
      } else if (!initialized) {
        logger.debug(s"$className computing value")
        initialized = true
        cachedValue
      } else if (!cachedValue.isInstanceOf[Iterator[_]]) {
        logger.debug(s"$className reusing cached value")
        cachedValue
      } else {
        logger.debug(s"$className recomputing value of type Iterator")
        createAndTime
      }
    logger.debug(s"$className returning value")
    returnValue
  }

  private var initialized = false
  private var timing: Option[Duration] = None
  private lazy val cachedValue: T = createAndTime

  /** Report the amount of time taken in milliseconds, or None if the value is cached
    * in memory or this stage has not been run yet.
    */
  def timeTaken: Option[Duration] = timing

  /** Call `create` but store time taken. */
  private def createAndTime: T = {
    val (result, duration) = Timing.time(this.create)
    timing = Some(duration)
    result
  }

  /** Persist the result of this step.
    * Once computed, write the result to the given artifact.
    * If the artifact we are using for persistence exists,
    * return the deserialized object rather than recomputing it.
    *
    * @tparam  A  the type of artifact being written to (i.e. directory, file)
    * @param  io  the serialization for data of type T
    * @param  artifactSource  creation of the artifact to be written
    */
  def persisted[A <: Artifact](
    io: SerializeToArtifact[T, A] with DeserializeFromArtifact[T, A],
    artifactSource: => A
  ): PersistedProducer[T, A] =
    new PersistedProducer(this, io, artifactSource)

  /** Default caching policy is set by the implementing class but can be overridden dynamically.
    *
    * When caching is enabled, an in-memory reference is stored to the output object so
    * subsequent calls to .get do not re-process.
    */
  def withCachingEnabled: Producer[T] = {
    if (cachingEnabled) {
      this
    } else {
      copy(cachingEnabled = () => true)
    }
  }

  /** Default caching policy is set by the implementing class but can be overridden dynamically. */
  def withCachingDisabled: Producer[T] = {
    if (cachingEnabled) {
      copy(cachingEnabled = () => false)
    } else {
      this
    }
  }

  def copy[T2](
    create: () => T2 = self.create _,
    stepInfo: () => PipelineStepInfo = self.stepInfo _,
    cachingEnabled: () => Boolean = self.cachingEnabled _
  ): Producer[T2] = {
    val _create = create
    val _stepInfo = stepInfo
    val _cachingEnabled = cachingEnabled
    new Producer[T2] {
      override def create: T2 = _create()

      override def stepInfo = _stepInfo()

      override def cachingEnabled = _cachingEnabled()
    }
  }
}

object Producer {
  /** A Pipeline step wrapper for in-memory data. */
  def fromMemory[T](data: T): Producer[T] = new Producer[T] with BasicPipelineStepInfo {
    override def create: T = data

    override def stepInfo: PipelineStepInfo =
      super.stepInfo.copy(
        className = data.getClass.getName,
        classVersion = data.hashCode.toHexString
      )
  }
}

trait CachingEnabled {
  def cachingEnabled: Boolean = true
}

trait CachingDisabled extends CachingEnabled {
  override def cachingEnabled: Boolean = false
}

class PersistedProducer[T, -A <: Artifact](
    step: Producer[T],
    io: SerializeToArtifact[T, A] with DeserializeFromArtifact[T, A],
    _artifact: A
) extends Producer[T] {
  self =>

  def artifact: Artifact = _artifact

  def create: T = {
    val className = stepInfo.className
    if (!artifact.exists) {
      val result = step.get
      logger.debug(s"$className writing to $artifact using $io")
      io.write(result, _artifact)
      if (result.isInstanceOf[Iterator[_]]) {
        logger.debug(s"$className reading type Iterator from $artifact using $io")
        io.read(_artifact)
      } else {
        result
      }
    } else {
      logger.debug(s"$className reading from $artifact using $io")
      io.read(_artifact)
    }
  }

  override def stepInfo = step.stepInfo.copy(outputLocation = Some(artifact.url))

  override def withCachingDisabled = {
    if (cachingEnabled) {
      new PersistedProducer(step, io, _artifact) with CachingDisabled
    } else {
      this
    }
  }

  override def withCachingEnabled: Producer[T] = {
    if (cachingEnabled) {
      this
    } else {
      new PersistedProducer(step, io, _artifact) with CachingEnabled
    }
  }
}

//
// Allow un-zipping of Producer instances
// e.g.:
//   val tupleProducer: Producer[List[Int], List[String]]
//   val Producer2(intList, stringList) = tupleProducer -> (("integers", "strings"))
//
object Producer2 {
  def unapply[T1, T2](
    input: (Producer[(T1, T2)], (String, String))
  ): Option[(Producer[T1], Producer[T2])] = {
    val (p, (name1, name2)) = input
    val p1 = p.copy(
      create = () => p.get._1,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name1")
    )

    val p2 = p.copy(
      create = () => p.get._2,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name2")
    )
    Some((p1, p2))
  }
}

object Producer3 {
  def unapply[T1, T2, T3](
    input: (Producer[(T1, T2, T3)], (String, String, String))
  ): Option[(Producer[T1], Producer[T2], Producer[T3])] = {
    val (p, (name1, name2, name3)) = input
    val p1 = p.copy(
      create = () => p.get._1,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name1")
    )

    val p2 = p.copy(
      create = () => p.get._2,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name2")
    )
    val p3 = p.copy(
      create = () => p.get._3,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name3")
    )
    Some((p1, p2, p3))
  }
}

object Producer4 {
  def unapply[T1, T2, T3, T4](
    input: (Producer[(T1, T2, T3, T4)], (String, String, String, String))
  ): Option[(Producer[T1], Producer[T2], Producer[T3], Producer[T4])] = {
    val (p, (name1, name2, name3, name4)) = input
    val p1 = p.copy(
      create = () => p.get._1,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name1")
    )

    val p2 = p.copy(
      create = () => p.get._2,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name2")
    )
    val p3 = p.copy(
      create = () => p.get._3,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name3")
    )
    val p4 = p.copy(
      create = () => p.get._4,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name4")
    )
    Some((p1, p2, p3, p4))
  }
}

object Producer5 {
  def unapply[T1, T2, T3, T4, T5](
    input: (Producer[(T1, T2, T3, T4, T5)], (String, String, String, String, String))
  ): Option[(Producer[T1], Producer[T2], Producer[T3], Producer[T4], Producer[T5])] = {
    val (p, (name1, name2, name3, name4, name5)) = input
    val p1 = p.copy(
      create = () => p.get._1,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name1")
    )

    val p2 = p.copy(
      create = () => p.get._2,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name2")
    )
    val p3 = p.copy(
      create = () => p.get._3,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name3")
    )
    val p4 = p.copy(
      create = () => p.get._4,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name4")
    )
    val p5 = p.copy(
      create = () => p.get._5,
      stepInfo = () => p.stepInfo.copy(className = s"${p.stepInfo.className}_$name5")
    )
    Some((p1, p2, p3, p4, p5))
  }
}
