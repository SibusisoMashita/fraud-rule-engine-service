"""Readiness-aware Docker ingress and bounded demonstration scaling, not a production scheduler."""
import csv
import io
import json
import math
import re

from stack import ROOT, Stack, check, command
from recovery import wait_for


def settings(path=ROOT / 'config/scaling-demo.example.json'):
    values = json.loads(path.read_text())
    fields = {'minReplicas', 'maxReplicas', 'targetSuccessesPerSecondPerReplica', 'scaleInUtilization',
              'scaleOutSamples', 'scaleInSamples', 'pollSeconds', 'cooldownSeconds',
              'busyRequestsPerSecond', 'busySeconds', 'quietSeconds', 'drainSeconds'}
    check(values.keys() == fields, 'Unknown/missing scaling setting')
    check(all(type(v) is int and v > 0 for k, v in values.items() if k != 'scaleInUtilization'), 'Scaling values must be positive integers')
    check(type(values['scaleInUtilization']) in (float, int) and 0 < values['scaleInUtilization'] < 1, 'Invalid scale-in utilization')
    check(values['minReplicas'] < values['maxReplicas'], 'Invalid replica range')
    limits = (ROOT / 'config/service.example.yaml').read_text()
    pool = int(re.search(r'dbPoolMaxConnectionsPerReplica: (\d+)', limits).group(1))
    budget = json.loads((ROOT / 'config/verification.example.json').read_text())
    check((values['maxReplicas'] + budget['rolloutSurge']) * pool <= budget['applicationConnectionBudget'], 'Replica/surge pool budget exceeded')
    check(values['busyRequestsPerSecond'] > values['minReplicas'] * values['targetSuccessesPerSecondPerReplica'], 'Demo load cannot trigger scale-out')
    check(values['drainSeconds'] < int(re.search(r'containerStopGraceSeconds: (\d+)', limits).group(1)), 'Drain exceeds container grace')
    return values


class BalancedStack(Stack):
    def __init__(self, output, verification, scaling):
        super().__init__(output, verification)
        self.scaling = scaling
        overlay = json.loads(command(['docker', 'compose', '-f', 'compose.yaml', '-f', 'compose.balanced.yaml', 'config', '--format', 'json']))
        proxy = overlay['services']['load-balancer']
        proxy['ports'] = [self.port_definition(8080)]
        proxy['environment']['FRAUD_MAX_REPLICAS'] = str(scaling['maxReplicas'])
        proxy['environment']['FRAUD_DEMO_HEADERS'] = 'true'
        self.config['services']['load-balancer'] = proxy
        self.config['services']['app'].pop('ports')
        self.config['services']['db'].pop('ports')
        self.save()

    def base(self, service='app'):
        return super().base('load-balancer' if service == 'app' else service)

    def start(self):
        self.base_cached = {}
        self.compose('up', '-d', '--no-build', '--wait', '--wait-timeout', str(self.settings['startupTimeoutSeconds']),
                     '--scale', 'app=' + str(self.scaling['minReplicas']), 'app', 'load-balancer')
        self.ready()
        self.wait_ready(self.scaling['minReplicas'])
        self.provision()

    def runtime(self, value):
        return self.compose('exec', '-T', 'load-balancer', 'nc', '-w', '1', '127.0.0.1', '9999', data=(value + '\n').encode()).decode()

    def set_state(self, name, state):
        check(not self.runtime('set server fraud/' + name + ' state ' + state).strip(),
              'Proxy rejected backend state change')

    def rows(self):
        return list(csv.DictReader(io.StringIO(self.runtime('show stat').lstrip('# '))))

    def servers(self):
        return [row for row in self.rows() if row['pxname'] == 'fraud' and row['svname'].startswith('app')]

    def backend_names(self, container):
        addresses = {network['IPAddress'] for network in container['NetworkSettings']['Networks'].values()}
        lines = self.runtime('show servers state fraud').splitlines()
        header = next(line for line in lines if line.startswith('#')).lstrip('# ').split()
        states = [dict(zip(header, line.split())) for line in lines if line and not line.startswith('#') and len(line.split()) > 1]
        return [row['srv_name'] for row in states if row['srv_addr'] in addresses]

    def wait_ready(self, count):
        wait_for(lambda: sum(row['status'] == 'UP' for row in self.servers()) == count,
                 self.settings['startupTimeoutSeconds'], 'Proxy did not discover ready replicas')

    def replicas(self):
        return self.compose('ps', '-q', 'app').decode().split()

    def scale(self, count):
        check(self.scaling['minReplicas'] <= count <= self.scaling['maxReplicas'], 'Replica bound exceeded')
        current = self.replicas()
        if count < len(current):
            # Select actual containers explicitly; never rely on Compose removal order.
            details = json.loads(command(['docker', 'inspect', *current]))
            details.sort(key=lambda x: int(x['Config']['Labels']['com.docker.compose.container-number']), reverse=True)
            for container in details[:len(current) - count]:
                names = self.backend_names(container)
                check(names, 'Cannot identify drain target')
                for name in names:
                    self.set_state(name, 'drain')
                wait_for(lambda: all(int(r['scur']) == 0 and int(r['qcur']) == 0 for r in self.servers() if r['svname'] in names),
                         self.scaling['drainSeconds'], 'In-flight requests did not drain')
                command(['docker', 'stop', container['Id']])
                command(['docker', 'rm', container['Id']])
        if count > len(current):
            for row in self.servers():
                if row['status'] != 'UP':
                    self.set_state(row['svname'], 'ready')
        self.compose('up', '-d', '--no-build', '--no-deps', '--scale', 'app=' + str(count), 'app')
        self.wait_ready(count)
        return count


class ScalingDecision:
    def __init__(self, values):
        self.values = values
        self.high = self.low = 0
        self.changed = float('-inf')

    def decide(self, rate, replicas, ready, now):
        v = self.values
        if rate is None or not math.isfinite(rate) or rate < 0 or ready != replicas or now - self.changed < v['cooldownSeconds']:
            self.high = self.low = 0
            return replicas
        self.high = self.high + 1 if rate > replicas * v['targetSuccessesPerSecondPerReplica'] else 0
        self.low = self.low + 1 if rate < (replicas - 1) * v['targetSuccessesPerSecondPerReplica'] * v['scaleInUtilization'] else 0
        target = replicas
        if self.high >= v['scaleOutSamples']:
            target = min(replicas + 1, v['maxReplicas'])
        elif self.low >= v['scaleInSamples']:
            target = max(replicas - 1, v['minReplicas'])
        if target != replicas:
            self.changed = now
            self.high = self.low = 0
        return target
