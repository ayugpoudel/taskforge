#!/usr/bin/env python3
"""Real CSV → HTTP upload → queued jobs → independently checked reports. No input data in logs."""
import argparse, concurrent.futures, json, os, subprocess, time, urllib.request, urllib.error
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--jobs',type=int,default=20);p.add_argument('--rows',type=int,default=20000);p.add_argument('--base',default='http://127.0.0.1:8080');p.add_argument('--output');p.add_argument('--require-processes',type=int,default=0)
a=p.parse_args()
values=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if line and not line.startswith('#'))
token=values['TF_AUTH_TOKENS'].split(',')[0].rsplit('=',1)[0]
def req(method,path,data=None,content='application/json'):
    r=urllib.request.Request(a.base+'/api'+path,data=data,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':content})
    with urllib.request.urlopen(r,timeout=60) as response:return json.load(response)
for n in range(120):
    try:
        urllib.request.urlopen(a.base+'/actuator/health',timeout=2).close();break
    except (OSError,urllib.error.URLError):time.sleep(.5)
else:raise SystemExit('API did not become healthy')
csv='n,double\n'+''.join(f'{i},{i*2}\n' for i in range(1,a.rows+1))
boundary='taskforge-e2e'
body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="numbers.csv"\r\nContent-Type: text/csv\r\n\r\n'+csv+f'\r\n--{boundary}--\r\n').encode()
dataset=req('POST','/datasets',body,'multipart/form-data; boundary='+boundary)['datasetId']
start=time.monotonic();submitted={}
for i in range(a.jobs):
    j=req('POST','/jobs',json.dumps({'datasetId':dataset}).encode());submitted[j['jobId']]=time.monotonic()
pending=set(submitted);results=[]
while pending:
    if time.monotonic()-start>180:raise SystemExit('Timed out waiting for durable completion')
    for job in list(pending):
        state=req('GET','/jobs/'+job)
        if state['status'] in ('FAILED','CANCELLED'):raise SystemExit('Unexpected terminal state: '+json.dumps(state))
        if state['status']=='SUCCEEDED':
            report=req('GET','/jobs/'+job+'/result')
            expected=a.rows*(a.rows+1)//2
            assert report['rowCount']==a.rows and report['invalidRows']==0
            assert report['columns'][0]['numeric']['sum']==expected
            assert report['columns'][1]['numeric']['sum']==2*expected
            assert report['columns'][0]['numeric']['mean']==(a.rows+1)/2
            results.append({'jobId':job,'state':state,'observedCompletionSeconds':time.monotonic()-submitted[job]})
            pending.remove(job)
    if pending:time.sleep(.1)
elapsed=time.monotonic()-start
if a.require_processes:
    ids=','.join("'"+j+"'" for j in submitted)
    sql=f"select count(distinct regexp_replace(worker_id,'-[0-9]+$','')) from job_attempts where job_id in ({ids})"
    count=int(subprocess.check_output(['docker','compose','exec','-T','postgres','psql','-U','taskforge','-d','taskforge','-Atc',sql],cwd=root,text=True).strip())
    assert count>=a.require_processes, f'Only {count} independent worker IDs processed this batch'
else:count=None
summary={'jobs':a.jobs,'rowsPerJob':a.rows,'inputBytes':len(csv.encode()),'seconds':elapsed,'jobsPerSecond':a.jobs/elapsed,'workerProcessesObserved':count,'results':results}
if a.output:
    target=Path(a.output);target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(summary,indent=2))
print(json.dumps({k:v for k,v in summary.items() if k!='results'},indent=2))
