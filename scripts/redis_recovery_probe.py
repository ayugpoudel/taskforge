#!/usr/bin/env python3
"""One explicit follow-up to the archived slow Redis interruption; not an SLA experiment."""
import json
import benchmark as b
import load_scenarios as scenarios
b.configure(4)
data=scenarios.dataset(250000)
result=b.batch(data,16,on_submitted=scenarios.outage('redis',3))
out={'environment':b.metadata(),'configurationChange':'explicit Redis connection establishment timeout of 2 seconds','outageSeconds':3,
     'archivedBeforeBatchSeconds':53.882,'followup':result,'faults':scenarios.faults,
     'limitation':'One follow-up under local conditions; does not establish a recovery-time guarantee or isolate all reconnect behavior.'}
(b.ROOT/'docs/benchmarks/redis-connection-followup.json').write_text(json.dumps(out,indent=2))
print(json.dumps({'jobs':result['jobs'],'durableBatchSeconds':result['durableBatchSeconds'],'additionalAttempts':sum(r['state']['attemptCount']-1 for r in result['results'])},indent=2))
