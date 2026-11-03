"""Bounded open-loop HTTP workload; latency starts at scheduled arrival."""
import concurrent.futures
import math
import threading
import time
from collections import Counter
from contextlib import ExitStack


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


def run(request, rate, seconds, workers, max_lag_ms, progress=None, *,
        include_samples=False, with_schedule=False, preallocate_workers=False,
        fixed_workers=False):
    count = round(rate * seconds)
    samples, futures = [], []
    dropped = 0
    def invoke(index, scheduled, slot):
        try:
            result = request(index, scheduled) if with_schedule else request(index)
            return {**result, 'latencyMillis': (time.monotonic() - scheduled) * 1000}
        finally:
            slot.release()

    # No executor backlog: a full generator drops an arrival and reports it.
    with ExitStack() as resources:
        if fixed_workers:
            # Every logical client owns one thread and one in-flight slot.
            executors = [resources.enter_context(concurrent.futures.ThreadPoolExecutor(max_workers=1))
                         for _ in range(workers)]
            slots = [threading.BoundedSemaphore(1) for _ in range(workers)]
        else:
            executors = [resources.enter_context(concurrent.futures.ThreadPoolExecutor(max_workers=workers))]
            slots = [threading.BoundedSemaphore(workers)]
        if preallocate_workers:
            # Exclude lazy thread creation from the offered arrival window.
            ready = threading.Barrier(workers + 1)
            starters = [executors[index if fixed_workers else 0].submit(ready.wait)
                        for index in range(workers)]
            ready.wait()
            for starter in starters:
                starter.result()
        start = time.monotonic()
        for index in range(count):
            worker = index % workers if fixed_workers else 0
            slot = slots[worker]
            scheduled = start + index / rate
            delay = scheduled - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            if (time.monotonic() - scheduled) * 1000 > max_lag_ms or not slot.acquire(False):
                dropped += 1
                continue
            futures.append(executors[worker].submit(invoke, index, scheduled, slot))
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
    if include_samples:
        result['samples'] = samples
    return result


def within_error_budget(result, settings):
    unexpected = sum(value for status, value in result['statuses'].items() if status not in ('200', '201', '429', '503'))
    return unexpected / max(1, result['sent']) < settings['maxUnexpectedErrorFraction']


def accepted(result, settings):
    return (result['sent'] == result['offered'] and result['dropped'] == 0 and result['denied'] == 0
            and result['p95Millis'] is not None and result['p95Millis'] < settings['p95Millis']
            and result['p99Millis'] < settings['p99Millis']
            and within_error_budget(result, settings))
