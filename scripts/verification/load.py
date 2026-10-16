"""Bounded open-loop HTTP workload; latency starts at scheduled arrival."""
import concurrent.futures
import math
import threading
import time
from collections import Counter


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)] if ordered else None


def summarize(samples, offered, duration, dropped):
    counts = Counter(str(row['status']) for row in samples)
    success = [row['latencyMillis'] for row in samples if row['status'] in (200, 201)]
    all_latency = [row['latencyMillis'] for row in samples]
    return {'offered': offered, 'sent': len(samples), 'completed': len(samples),
            'dropped': dropped, 'denied': sum(counts.get(str(n), 0) for n in (429, 503)),
            'statuses': dict(counts), 'durationSeconds': duration,
            'successfulRequestsPerSecond': len(success) / duration,
            'p95Millis': percentile(all_latency, .95), 'p99Millis': percentile(all_latency, .99),
            'successfulP95Millis': percentile(success, .95),
            'successfulP99Millis': percentile(success, .99)}


def run(request, rate, seconds, workers, max_lag_ms, progress=None):
    count = round(rate * seconds)
    samples, futures = [], []
    slots = threading.BoundedSemaphore(workers)
    dropped = 0
    start = time.monotonic()

    def invoke(index, scheduled):
        try:
            result = request(index)
            return {**result, 'latencyMillis': (time.monotonic() - scheduled) * 1000}
        finally:
            slots.release()

    # No executor backlog: a full generator drops an arrival and reports it.
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        for index in range(count):
            scheduled = start + index / rate
            delay = scheduled - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            if (time.monotonic() - scheduled) * 1000 > max_lag_ms or not slots.acquire(False):
                dropped += 1
                continue
            futures.append(executor.submit(invoke, index, scheduled))
            if progress and index % max(1, round(rate * 30)) == 0:
                progress(index, count)
        for future in futures:
            samples.append(future.result())
    # Keep the offered window in the denominator even if the last response is fast.
    remaining = start + seconds - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)
    result = summarize(samples, count, seconds, dropped)
    elapsed = time.monotonic() - start
    result.update(observedSeconds=elapsed, drainSeconds=max(0, elapsed - seconds),
                  successfulRequestsPerSecond=sum(row['status'] in (200, 201) for row in samples) / elapsed)
    return result


def within_error_budget(result, settings):
    unexpected = sum(value for status, value in result['statuses'].items() if status not in ('200', '201', '429', '503'))
    return unexpected / max(1, result['sent']) < settings['maxUnexpectedErrorFraction']


def accepted(result, settings):
    return (result['sent'] == result['offered'] and result['dropped'] == 0 and result['denied'] == 0
            and result['p95Millis'] is not None and result['p95Millis'] < settings['p95Millis']
            and result['p99Millis'] < settings['p99Millis']
            and within_error_budget(result, settings))
