package com.linroid.ketch.core.segment

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.engine.TransferWatchdog
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SequentialLengthMismatchException(
  message: String = "Sequential response length mismatch",
) : IllegalStateException(message)

internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor,
  private val taskLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  taskId: String? = null,
  private val watchdog: TransferWatchdog = TransferWatchdog.Off,
) {
  private val log = KetchLogger("SegmentDownloader")
  private val owner = taskId?.let { " for taskId=$it" }.orEmpty()

  /**
   * Downloads [lane] with one Range request from its offset to its end when [useRange] is `true`,
   * or a sequential full-resource request (`range = null`) starting at byte zero when `false`.
   * The response is closed early when another connection takes over the lane's tail meanwhile.
   */
  suspend fun download(
    url: String,
    lane: SegmentLane,
    headers: Map<String, String> = emptyMap(),
    handle: ConnectionHandle = ConnectionHandle.None,
    useRange: Boolean = true,
  ) {
    if (lane.isComplete) return
    if (!useRange) {
      require(lane.start == 0L && lane.offset == 0L) {
        "Sequential full-resource download must start at byte 0 " +
          "(start=${lane.start}, offset=${lane.offset})"
      }
    }
    val range = if (useRange) lane.offset..lane.end else null
    if (range != null) {
      log.d {
        "Starting range ${range.first}..${range.last}$owner " +
          "(${range.last - range.first + 1} bytes remaining)"
      }
    } else {
      log.d {
        "Starting sequential full-resource request 0..${lane.end}$owner " +
          "(${lane.end + 1} bytes expected)"
      }
    }
    var writeOffset = range?.first ?: 0L

    watchdog.watch { monitor ->
      httpEngine.download(url, range, headers) { data ->
        currentCoroutineContext().ensureActive()
        monitor.received(data.size)
        val allowed = lane.admit(data.size)
        if (allowed > 0) {
          monitor.throttled {
            taskLimiter.acquire(allowed)
            globalLimiter.acquire(allowed)
          }
          val chunk = if (allowed == data.size) data else data.copyOf(allowed)
          try {
            fileAccessor.writeAt(writeOffset, chunk)
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is KetchError) throw e
            throw KetchError.Disk(e)
          }
          writeOffset += allowed
          handle.received(allowed)
          // Ends the response when another connection took the rest of the range.
          lane.commit(allowed)
        }
        if (allowed < data.size) {
          throw KetchError.Unsupported(
            if (useRange) {
              IllegalStateException("Response exceeds segment boundary")
            } else {
              SequentialLengthMismatchException(
                "Sequential response exceeded expected length ${lane.end + 1}",
              )
            },
          )
        }
      }
    }

    if (!lane.isComplete) {
      if (!useRange) {
        val expectedBytes = lane.end + 1
        log.w {
          "Sequential response length mismatch$owner: " +
            "received $writeOffset of $expectedBytes bytes"
        }
        throw KetchError.Unsupported(
          SequentialLengthMismatchException(
            "Sequential response length mismatch: received $writeOffset of $expectedBytes bytes",
          ),
        )
      }
      val startOffset = range!!.first
      log.w {
        "Incomplete range $startOffset..${lane.end}$owner: " +
          "downloaded ${writeOffset - startOffset}/${lane.end - startOffset + 1} bytes"
      }
      throw KetchError.Network(
        Exception(
          "Connection closed prematurely: received " +
            "${writeOffset - startOffset} of ${lane.end - startOffset + 1} bytes"
        )
      )
    }
    log.d { "Completed ${if (useRange) "range ${range!!.first}" else "sequential 0"}..${lane.end}$owner" }
  }
}
