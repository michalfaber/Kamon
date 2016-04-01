/*
 * =========================================================================================
 * Copyright © 2013-2014 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.trace

import java.util.concurrent.ConcurrentLinkedQueue

import akka.event.LoggingAdapter
import kamon.Kamon
import kamon.metric.{ SegmentMetrics, TraceMetrics }
import kamon.util.{ NanoInterval, RelativeNanoTimestamp }

import scala.annotation.tailrec

private[kamon] class MetricsOnlyContext(traceName: String, val token: String, izOpen: Boolean, val levelOfDetail: LevelOfDetail,
  val startTimestamp: RelativeNanoTimestamp, log: LoggingAdapter)
    extends TraceContext {

  @volatile private var _name = traceName
  @volatile private var _isOpen = izOpen
  @volatile private var _isFinishedWithError = false
  @volatile protected var _elapsedTime = NanoInterval.default

  private val _finishedSegments = new ConcurrentLinkedQueue[SegmentLatencyData]()
  private val _traceLocalStorage = new TraceLocalStorage

  def rename(newName: String): Unit =
    if (isOpen)
      _name = newName
    else
      log.warning("Can't rename trace from [{}] to [{}] because the trace is already closed.", name, newName)

  def name: String = _name
  def isEmpty: Boolean = false
  def isOpen: Boolean = _isOpen
  def isFinishedWithError: Boolean = _isFinishedWithError
  def addMetadata(key: String, value: String): Unit = {}

  def finish(): Unit = {
    _isOpen = false
    val traceElapsedTime = NanoInterval.since(startTimestamp)
    _elapsedTime = traceElapsedTime

    if (Kamon.metrics.shouldTrack(name, TraceMetrics.category)) {
      val traceEntity = Kamon.metrics.entity(TraceMetrics, name)
      traceEntity.elapsedTime.record(traceElapsedTime.nanos)
      if (isFinishedWithError) traceEntity.traceErrors.increment()
    }

    drainFinishedSegments()
  }

  def finishWithError(cause: Throwable): Unit = {
    //we should do something with the Throwable in a near future
    _isFinishedWithError = true
    finish()
  }

  def startSegment(segmentName: String, category: String, library: String): Segment =
    new MetricsOnlySegment(segmentName, category, library)

  @tailrec private def drainFinishedSegments(): Unit = {
    val segment = _finishedSegments.poll()
    if (segment != null) {
      val segmentTags = Map(
        "trace" -> name,
        "category" -> segment.category,
        "library" -> segment.library)

      if (Kamon.metrics.shouldTrack(segment.name, SegmentMetrics.category)) {
        val segmentEntity = Kamon.metrics.entity(SegmentMetrics, segment.name, segmentTags)
        segmentEntity.elapsedTime.record(segment.duration.nanos)
        if (segment.isFinishedWithError) segmentEntity.segmentErrors.increment()
      }
      drainFinishedSegments()
    }
  }

  protected def finishSegment(segmentName: String, category: String, library: String, duration: NanoInterval, isFinishedWithError: Boolean = false): Unit = {
    _finishedSegments.add(SegmentLatencyData(segmentName, category, library, duration, isFinishedWithError))

    if (isClosed) {
      drainFinishedSegments()
    }
  }

  // Should only be used by the TraceLocal utilities.
  def traceLocalStorage: TraceLocalStorage = _traceLocalStorage

  // Handle with care and make sure that the trace is closed before calling this method, otherwise NanoInterval.default
  // will be returned.
  def elapsedTime: NanoInterval = _elapsedTime

  class MetricsOnlySegment(segmentName: String, val category: String, val library: String) extends Segment {
    private val _startTimestamp = RelativeNanoTimestamp.now
    @volatile private var _segmentName = segmentName
    @volatile private var _elapsedTime = NanoInterval.default
    @volatile private var _isOpen = true
    @volatile private var _isFinishedWithError = false

    def name: String = _segmentName
    def isEmpty: Boolean = false
    def addMetadata(key: String, value: String): Unit = {}
    def isOpen: Boolean = _isOpen
    def isFinishedWithError: Boolean = _isFinishedWithError

    def rename(newName: String): Unit =
      if (isOpen)
        _segmentName = newName
      else
        log.warning("Can't rename segment from [{}] to [{}] because the segment is already closed.", name, newName)

    def finishWithError(cause: Throwable): Unit = {
      //we should do something with the Throwable in a near future
      _isFinishedWithError = true
      finish
    }

    def finish: Unit = {
      _isOpen = false
      val segmentElapsedTime = NanoInterval.since(_startTimestamp)
      _elapsedTime = segmentElapsedTime

      finishSegment(name, category, library, segmentElapsedTime, isFinishedWithError)
    }

    // Handle with care and make sure that the segment is closed before calling this method, otherwise
    // NanoInterval.default will be returned.
    def elapsedTime: NanoInterval = _elapsedTime
    def startTimestamp: RelativeNanoTimestamp = _startTimestamp
  }
}

case class SegmentLatencyData(name: String, category: String, library: String, duration: NanoInterval, isFinishedWithError: Boolean)
