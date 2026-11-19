#!/usr/bin/env python3
"""Diagnose a deliberately stopped worker pool, then prove queued jobs recover."""
import base64,json,subprocess,time,urllib.request,urllib.parse
from pathlib import Path
root=Path(__file__).resolve().parents[1]
values=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if line and not line.startswith('#'))
token=values['TF_AUTH_TOKENS'].split(',')[0].rsplit('=',1)[0]
def fetch(url,data=None,headers=None):
    with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=headers or {}),timeout=10) as response:return json.load(response)
def api(path,data=None,content='application/json'):
    return fetch('http://127.0.0.1:8080/api'+path,data,{'Authorization':'Bearer '+token,'Content-Type':content})
def metric(query):
    result=fetch('http://127.0.0.1:19090/api/v1/query?'+urllib.parse.urlencode({'query':query}))['data']['result']
    return None if not result else float(result[0]['value'][1])
def wait(condition,seconds=60):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            if condition():return
        except (OSError,KeyError,TypeError):pass
        time.sleep(.5)
    raise AssertionError('Condition did not become true')
wait(lambda:fetch('http://127.0.0.1:13000/api/health')['database']=='ok')
login=base64.b64encode(('taskforge:'+values['TF_GRAFANA_PASSWORD']).encode()).decode()
dashboard=fetch('http://127.0.0.1:13000/api/dashboards/uid/taskforge',headers={'Authorization':'Basic '+login})
assert len(dashboard['dashboard']['panels'])>=10
subprocess.run(['docker','compose','stop','worker'],cwd=root,check=True,stdout=subprocess.DEVNULL)
ids=[]
try:
    boundary='obs-check'
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="numbers.csv"\r\nContent-Type: text/csv\r\n\r\na\n1\n2\n\r\n--{boundary}--\r\n').encode()
    dataset=api('/datasets',body,'multipart/form-data; boundary='+boundary)['datasetId']
    for i in range(4):ids.append(api('/jobs',json.dumps({'datasetId':dataset}).encode())['jobId'])
    wait(lambda:metric('sum(taskforge_jobs_current{status="QUEUED"})')>=4)
    wait(lambda:metric('taskforge_queue_oldest_age')>=1)
    paused={'queued':metric('sum(taskforge_jobs_current{status="QUEUED"})'),'oldestAgeSeconds':metric('taskforge_queue_oldest_age'),'outboxPending':metric('taskforge_outbox_pending')}
finally:
    subprocess.run(['docker','compose','up','-d','--scale','worker=2','--wait','worker'],cwd=root,check=True,stdout=subprocess.DEVNULL)
wait(lambda:all(api('/jobs/'+j)['status']=='SUCCEEDED' for j in ids))
wait(lambda:metric('sum(taskforge_jobs_current{status="QUEUED"})')==0)
for job in ids:assert api('/jobs/'+job+'/result')['columns'][0]['numeric']['sum']==3
out={'scenario':'worker pool stopped after healthy startup; accept four real CSV jobs; resume workers',
     'dashboardPanels':len(dashboard['dashboard']['panels']),'paused':paused,'recoveredJobs':len(ids),
     'queuedAfterRecovery':metric('sum(taskforge_jobs_current{status="QUEUED"})'),'diagnosis':'Queued age rose while the deliberately stopped workers could not claim work; jobs completed after restart.'}
path=root/'docs/evidence/phase6-observability.json';path.write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))
