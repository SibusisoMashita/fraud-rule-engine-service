"""Exercise production proxy error rules in an owned, dependency-free fixture."""
import copy
import csv
import io
import json
import re
import urllib.error
import urllib.request
import uuid

from stack import ROOT, check


DETAILS = {
    'NO_READY_BACKEND': 'No application instance is ready to receive this request.',
    'BACKEND_UNAVAILABLE': 'The proxy could not confirm the outcome of this request.',
}


def check_problem(response, code, outcome):
    """Check the safe proxy contract without including the response in diagnostics."""
    status, payload, headers = response
    check(status == 503, 'Proxy did not return HTTP 503')
    check(headers.get('content-type', '').split(';', 1)[0].strip() == 'application/problem+json',
          'Proxy error content type differs from the API contract')
    check(headers.get('cache-control') == 'no-store', 'Proxy error may be cached')
    check(headers.get('retry-after') == '1', 'Proxy error omitted its bounded retry hint')
    try:
        problem = json.loads(payload)
    except (ValueError, UnicodeError):
        raise AssertionError('Proxy did not return a JSON problem') from None
    expected = {'type', 'title', 'status', 'detail', 'code', 'correlationId', 'retryable', 'outcome'}
    check(isinstance(problem, dict) and problem.keys() == expected,
          'Proxy problem contains missing or unexpected fields')
    check(problem['type'] == 'about:blank' and problem['title'] == 'Service Unavailable',
          'Proxy problem has an unexpected type or title')
    check(type(problem['status']) is int and problem['status'] == 503,
          'Proxy problem has an unexpected status')
    check(code in DETAILS and problem['code'] == code and problem['detail'] == DETAILS[code],
          'Proxy problem has an unexpected code or unsafe detail')
    check(problem['outcome'] == outcome and problem['retryable'] is True,
          'Proxy problem has an incorrect recovery classification')
    correlation = problem['correlationId']
    try:
        parsed = uuid.UUID(correlation) if isinstance(correlation, str) else None
    except ValueError:
        parsed = None
    check(parsed is not None and parsed.version == 4 and str(parsed) == correlation,
          'Proxy correlation ID is not a generated UUID')
    check(headers.get('x-correlation-id') == correlation, 'Proxy correlation header/body differ')
    return problem


def run(stack, record):
    """Distinguish pre-dispatch rejection, a native error and an upstream 503."""
    service = 'proxy-error-fixture'
    config = (ROOT / 'docker/haproxy.cfg').read_text()
    # Exercise the production response rules while making transport failure deterministic.
    config, replaced = re.subn(r'^    server-template app .*$',
                              '    server fixture 127.0.0.1:9001 no-check init-state fully-up',
                              config, flags=re.MULTILINE)
    check(replaced == 1, 'Cannot identify production proxy backend for the error fixture')
    upstream_body = b'{"marker":"upstream-503-preserved"}'
    upstream_correlation = 'fixture-application-correlation'
    config += (
        '\nfrontend fixture_upstream\n'
        '    bind 127.0.0.1:9002\n'
        '    http-request return status 503 content-type application/problem+json '
        "string '" + upstream_body.decode() + "' "
        'hdr X-Fixture-Upstream preserved '
        'hdr X-Correlation-ID ' + upstream_correlation + '\n'
    )
    fixture_file = stack.output / 'proxy-error-fixture.cfg'
    fixture_file.write_text(config)
    fixture = copy.deepcopy(stack.config['services']['load-balancer'])
    fixture['profiles'] = ['proxy-error-fixture']
    fixture['ports'] = [stack.port_definition(8080)]
    fixture['volumes'] = [{'type': 'bind', 'source': str(fixture_file),
                           'target': '/usr/local/etc/haproxy/haproxy.cfg', 'read_only': True}]
    fixture.pop('depends_on', None)
    stack.config['services'][service] = fixture
    stack.save()

    def runtime(value):
        return stack.compose('exec', '-T', service, 'nc', '-w', '1', '127.0.0.1', '9999',
                             data=(value + '\n').encode()).decode()

    def state(value):
        check(not runtime('set server fraud/fixture state ' + value).strip(),
              'Proxy fixture rejected the controlled server state')

    def counters():
        rows = csv.DictReader(io.StringIO(runtime('show stat').lstrip('# ')))
        return next(row for row in rows if row['pxname'] == 'fraud' and row['svname'] == 'fixture')

    sentinel = 'fixture-input-must-not-appear'
    forged_correlation = '00000000-0000-4000-8000-000000000000'

    def request():
        request = urllib.request.Request(
            stack.base(service) + '/api/v1/transactions?private=' + sentinel,
            data=json.dumps({'private': sentinel}).encode(),
            headers={'Content-Type': 'application/json', 'X-API-Key': sentinel,
                     'X-Correlation-ID': forged_correlation})
        try:
            response = urllib.request.urlopen(request, timeout=stack.settings['requestTimeoutSeconds'])
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = response.status, response.read(), {key.lower(): value for key, value in response.headers.items()}
        check(sentinel.encode() not in result[1] and sentinel not in str(result[2]),
              'Proxy reflected private request input')
        return result

    correlations = set()

    def problem(code, outcome):
        body = check_problem(request(), code, outcome)
        correlation = body['correlationId']
        check(correlation != forged_correlation and correlation not in correlations,
              'Proxy reused an incoming or previous correlation ID')
        correlations.add(correlation)
        return {'status': 503, 'code': code, 'outcome': outcome, 'correlationId': correlation}

    try:
        stack.compose('--profile', 'proxy-error-fixture', 'up', '-d', '--no-build', '--no-deps', '--wait',
                      '--wait-timeout', str(stack.settings['startupTimeoutSeconds']), service)
        state('maint')
        rejected = [problem('NO_READY_BACKEND', 'NOT_ACCEPTED') for _ in range(2)]
        state('ready')
        before = counters()
        unavailable = problem('BACKEND_UNAVAILABLE', 'UNKNOWN')
        after = counters()
        check(int(after['econ']) - int(before['econ']) == 1 and int(after['wretr']) == int(before['wretr']),
              'Proxy retried a failed write connection')
        runtime('set server fraud/fixture addr 127.0.0.1 port 9002')
        status, payload, headers = request()
        check(status == 503 and payload == upstream_body
              and headers.get('x-fixture-upstream') == 'preserved'
              and headers.get('x-correlation-id') == upstream_correlation,
              'Proxy replaced an application-generated 503 response')
        record('proxy-error-contract', {'preDispatch': rejected, 'transportFailure': unavailable,
                                        'upstream503Preserved': True, 'automaticRetries': 0})
    finally:
        # Keep the service in the owned Compose file so Stack.close can retry cleanup if needed.
        try:
            stack.compose('--profile', 'proxy-error-fixture', 'stop', service)
        finally:
            stack.compose('--profile', 'proxy-error-fixture', 'rm', '-f', service)
