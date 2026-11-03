"""Measured HTTP/1.1 transport, shared by host and Docker latency runs.

One worker owns each connection. Failed writes are recorded once, never retried.
Jobs arrive on stdin so credentials do not enter command arguments or reports.
"""
import hashlib
import http.client
import json
import platform
import re
import socket
import sys
import threading
import time
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit

import load


def payload(job, index):
    """Regenerate an identical synthetic write for explicit reconciliation."""
    customer = job['runId'] + ('-hot' if job['workload'] == 'hot' else '-' + str(index))
    return {**job['event'], 'eventId': job['runId'] + '-' + str(index),
            'customerId': customer, 'instrumentId': 'card-' + customer,
            'occurredAt': job['occurredAt']}


class MeasuredSocket(socket.socket):
    first_byte = None
    deadline = None

    def recv_into(self, buffer, nbytes=0, flags=0):
        if self.deadline is not None:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError()
            self.settimeout(remaining)
        count = super().recv_into(buffer, nbytes, flags)
        if count and self.first_byte is None:
            self.first_byte = time.monotonic()
        return count


class MeasuredConnection(http.client.HTTPConnection):
    """Separate DNS, TCP connect and first network response-byte timing."""
    def __init__(self, host, port, connect_timeout):
        super().__init__(host, port, timeout=connect_timeout)
        self.connect_timeout = connect_timeout
        self.opens = 0
        self.dns_ms = self.connect_ms = 0.0

    def connect(self):
        began = time.monotonic()
        self.connect_ms = 0.0
        try:
            addresses = socket.getaddrinfo(self.host, self.port, 0, socket.SOCK_STREAM)
        finally:
            self.dns_ms = (time.monotonic() - began) * 1000
        began = time.monotonic()
        deadline = began + self.connect_timeout
        failure = None
        for family, kind, protocol, _, address in addresses:
            candidate = MeasuredSocket(family, kind, protocol)
            try:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError()
                candidate.settimeout(remaining)
                candidate.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                candidate.connect(address)
                self.sock = candidate
                self.opens += 1
                return
            except OSError as error:
                failure = error
                candidate.close()
            finally:
                self.connect_ms = (time.monotonic() - began) * 1000
        raise failure if failure is not None else OSError()


def safe_header(value, pattern):
    return value if value is not None and re.fullmatch(pattern, value) else None


def response_validation(job, status, body):
    if status not in (200, 201):
        return None
    try:
        result = json.loads(body)
        if job['workload'] == 'read':
            items = result.get('items')
            return None if isinstance(items, list) and len(items) == job['readPageSize'] else 'INVALID_READ_RESPONSE'
        evaluation = result['evaluation']
        if status != 201:
            return 'UNEXPECTED_REPLAY'
        if evaluation['policyHash'] != job['policyHash']:
            return 'POLICY_HASH_MISMATCH'
        if len(evaluation['ruleResults']) != job['expectedRuleCount']:
            return 'RULE_COUNT_MISMATCH'
    except (ValueError, KeyError, TypeError, AttributeError):
        return 'INVALID_SUCCESS_RESPONSE'
    return None


def quantiles(values):
    return {name: load.percentile(values, fraction) for name, fraction in
            (('p50', .5), ('p95', .95), ('p99', .99), ('p99.9', .999), ('max', 1))}


def measure(job):
    endpoints = [urlsplit(url) for url in job['baseUrls']]
    if any(url.scheme != 'http' or not url.hostname or url.username or url.password
           or url.query or url.fragment for url in endpoints):
        raise ValueError('Expected plain HTTP endpoints without credentials or queries')
    if job['connectionMode'] not in ('fresh', 'reuse') or job['workload'] not in ('read', 'distinct', 'hot'):
        raise ValueError('Unsupported transport mode or workload')
    local, states, guard = threading.local(), [], threading.Lock()

    def worker(index):
        if not hasattr(local, 'state'):
            with guard:
                local.state = {'worker': index % job['workers'], 'connections': {}, 'requests': 0}
                states.append(local.state)
        return local.state

    def request(index, scheduled):
        state = worker(index)
        destination = index % len(endpoints)
        principal = index % len(job['keys'])
        url = endpoints[destination]
        connections = state['connections']
        connection = connections.get(destination)
        if connection is None:
            connection = MeasuredConnection(url.hostname, url.port or 80, job['connectTimeoutSeconds'])
            connections[destination] = connection
        if job['connectionMode'] == 'fresh':
            connection.close()
        first = connection.sock is None
        before = connection.opens
        began = time.monotonic()
        deadline = began + job['requestTimeoutSeconds']
        body_started = None
        row = {'index': index, 'worker': state['worker'], 'destinationIndex': destination,
               'principalIndex': principal, 'status': 0, 'responseStatus': None, 'transportError': None,
               'validationError': None, 'correlationId': None, 'backend': None,
               'retryAfter': None, 'firstRequestOnConnection': first,
               'firstRequest': state['requests'] == 0,
               'firstRequestOnWorker': state['requests'] == 0,
               'schedulerDelayMillis': (began - scheduled) * 1000,
               'dnsMillis': None, 'connectMillis': None, 'sendMillis': None,
               'ttfbMillis': None, 'responseHeadersMillis': None, 'bodyMillis': None,
               'responseMillis': None, 'validationMillis': None}
        state['requests'] += 1
        headers = {'X-API-Key': job['keys'][principal], 'Accept': 'application/json'}
        if job['workload'] == 'read':
            method, body = 'GET', None
            path = '/api/v1/evaluations?pageSize=' + str(job['readPageSize'])
        else:
            event = payload(job, index)
            row['eventId'] = event['eventId']
            method, body = 'POST', json.dumps(event, separators=(',', ':')).encode()
            headers['Content-Type'] = 'application/json'
            path = '/api/v1/transactions'

        def remaining(network):
            value = deadline - time.monotonic()
            if value <= 0:
                raise TimeoutError()
            network.settimeout(value)

        try:
            if first:
                connection.connect()
                row.update(dnsMillis=connection.dns_ms, connectMillis=connection.connect_ms)
            else:
                row.update(dnsMillis=0.0, connectMillis=0.0)
            network = connection.sock
            network.first_byte = None
            network.deadline = deadline
            remaining(network)
            send_start = time.monotonic()
            connection.request(method, url.path.rstrip('/') + path, body=body, headers=headers)
            sent = time.monotonic()
            row['sendMillis'] = (sent - send_start) * 1000
            remaining(network)
            response = connection.getresponse()
            row['responseStatus'] = response.status
            row['correlationId'] = safe_header(response.getheader('X-Correlation-ID'),
                                              r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}')
            row['backend'] = safe_header(response.getheader('X-Demo-Backend'), r'app[0-9]{1,4}')
            row['retryAfter'] = safe_header(response.getheader('Retry-After'), r'[0-9]{1,10}')
            received = time.monotonic()
            body_started = received
            first_byte = network.first_byte
            row['ttfbMillis'] = max(0, (first_byte - sent) * 1000) if first_byte is not None else None
            row['responseHeadersMillis'] = (received - sent) * 1000
            # Do not retain bodies: they contain transaction data, and are unnecessary for timing.
            chunks = []
            while not response.isclosed():
                remaining(network)
                chunk = response.read1(65536)
                if not chunk:
                    break
                chunks.append(chunk)
            if response.length not in (None, 0):
                raise http.client.IncompleteRead(b'', response.length)
            response_completed = time.monotonic()
            row['bodyMillis'] = (response_completed - received) * 1000
            row['responseMillis'] = (response_completed - began) * 1000
            row['status'] = response.status
            row['validationError'] = response_validation(job, row['status'], b''.join(chunks))
            row['validationMillis'] = (time.monotonic() - response_completed) * 1000
            response.close()
        except (OSError, http.client.HTTPException) as failure:
            row['transportError'] = type(failure).__name__
            if body_started is not None:
                row['bodyMillis'] = (time.monotonic() - body_started) * 1000
            connection.close()
        finally:
            if first:
                row.update(dnsMillis=connection.dns_ms, connectMillis=connection.connect_ms)
            row['connectionOpens'] = connection.opens - before
            row['totalMillis'] = (time.monotonic() - began) * 1000
            if job['connectionMode'] == 'fresh':
                connection.close()
        return row

    try:
        summary = load.run(request, job['ratePerSecond'], job['durationSeconds'], job['workers'],
                           job['maxGeneratorLagMillis'], include_samples=True, with_schedule=True,
                           preallocate_workers=job['preallocateWorkers'], fixed_workers=True)
    finally:
        for state in states:
            for connection in state['connections'].values():
                connection.close()
    samples = summary.pop('samples')
    for row in samples:
        row['slow'] = row['latencyMillis'] >= job['slowRequestMillis']
    timing_fields = ('latencyMillis', 'schedulerDelayMillis', 'dnsMillis', 'connectMillis',
                     'sendMillis', 'ttfbMillis', 'responseHeadersMillis', 'bodyMillis',
                     'responseMillis', 'validationMillis', 'totalMillis')
    summary.update(timings={field: quantiles([row[field] for row in samples if row[field] is not None])
                            for field in timing_fields},
                   connectionOpens=sum(row['connectionOpens'] for row in samples),
                   validationErrors=dict(Counter(row['validationError'] for row in samples if row['validationError'])),
                   transportErrors=dict(Counter(row['transportError'] for row in samples if row['transportError'])),
                   slowRequests=sum(row['slow'] for row in samples),
                   retries=0, arrivalModel='open-loop evenly-spaced; fixed round-robin clients; busy-client arrivals dropped')
    for prefix, subset in (('', samples), ('successful', [row for row in samples if row['status'] in (200, 201)])):
        for name, fraction in (('p50', .5), ('p95', .95), ('p99', .99), ('p999', .999), ('max', 1)):
            key = prefix + name[0].upper() + name[1:] + 'Millis' if prefix else name + 'Millis'
            summary[key] = load.percentile([row['latencyMillis'] for row in subset], fraction)
    for label, first in (('firstRequest', True), ('subsequentRequest', False)):
        values = [row['latencyMillis'] for row in samples if row['firstRequest'] == first]
        summary[label] = {'count': len(values), 'latencyMillis': quantiles(values)}
    for label, first in (('newConnection', True), ('reusedConnection', False)):
        values = [row['latencyMillis'] for row in samples if row['firstRequestOnConnection'] == first]
        summary[label] = {'count': len(values), 'latencyMillis': quantiles(values)}
    digest = hashlib.sha256()
    for source in (__file__, load.__file__):
        digest.update(Path(source).read_bytes())
    return {'summary': summary, 'samples': samples,
            'client': {'python': platform.python_version(), 'platform': platform.platform(),
                       'transport': 'stdlib http.client HTTP/1.1', 'sourceSha256': digest.hexdigest(),
                       'connectionMode': job['connectionMode'], 'workersUsed': len(states),
                       'preallocatedWorkers': job['preallocateWorkers'],
                       'workerScheduling': 'fixed round-robin by request index; one thread and in-flight slot per client',
                       'ttfbBoundary': 'request sent to first response byte received by socket',
                       'responseBoundary': 'worker request start to complete HTTP body, before JSON validation',
                       'latencyBoundary': 'scheduled arrival to response validation and cleanup completion or failure'}}


def main():
    try:
        result = measure(json.load(sys.stdin))
    except Exception as failure:
        # Exception messages can contain credentials, submitted values, and private URLs.
        print(json.dumps({'error': type(failure).__name__}))
        return 1
    print(json.dumps(result, allow_nan=False, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    sys.exit(main())
