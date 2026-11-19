#!/usr/bin/env python3
"""Controlled finite-batch experiments; raw durable timestamps, not polling time, define throughput."""
import argparse,concurrent.futures,datetime,hashlib,json,math,os,platform,statistics,subprocess,threading,time,urllib.request,urllib.error,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BASE='http://127.0.0.1:8080'
ENV=dict(os.environ)
COMPOSE=['docker','compose','-f','compose.yml','-f','compose.benchmark.yml']
secrets=dict(line.split('=',1) for line in (ROOT/'.env').read_text().splitlines() if line and not line.startswith('#'))
TOKEN=secrets['TF_AUTH_TOKENS'].split(',')[0].rsplit('=',1)[0]
def command(args,**kw):return subprocess.check_output(args,cwd=ROOT,text=True,**kw).strip()
def request(method,path,body=None,key=None):
    data=None if body is None else json.dumps(body).encode()
    headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'}
    if key:headers['Idempotency-Key']=key
    req=urllib.request.Request(BASE+'/api'+path,data=data,method=method,headers=headers)
    with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
def wait(condition,seconds=600):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            value=condition()
            if value:return value
        except (OSError,urllib.error.URLError):pass
        time.sleep(.1)
    raise AssertionError('Timed out awaiting condition')
def iso(value):return datetime.datetime.fromisoformat(value.replace('Z','+00:00'))
def percentile(values,p):
    a=sorted(values);pos=(len(a)-1)*p;lo=int(pos);hi=math.ceil(pos)
    return a[lo]+(a[hi]-a[lo])*(pos-lo)
def summarize(values):return {'n':len(values),'mean':statistics.mean(values),'p50':percentile(values,.5),'p95':percentile(values,.95),'p99':percentile(values,.99),'max':max(values)}
def upload(rows):
    csv='n,double\n'+''.join(f'{i},{i*2}\n' for i in range(1,rows+1))
    boundary='taskforge-benchmark'
    data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="numbers.csv"\r\nContent-Type: text/csv\r\n\r\n'+csv+f'\r\n--{boundary}--\r\n').encode()
    req=urllib.request.Request(BASE+'/api/datasets',data=data,method='POST',headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'multipart/form-data; boundary='+boundary})
    with urllib.request.urlopen(req,timeout=60) as r:dataset=json.load(r)['datasetId']
    return {'datasetId':dataset,'rows':rows,'bytes':len(csv.encode()),'sha256':hashlib.sha256(csv.encode()).hexdigest()}
class Samples:
    def __init__(self):self.stop=threading.Event();self.rows=[];self.thread=threading.Thread(target=self.run,daemon=True)
    def run(self):
        while not self.stop.is_set():
            try:
                text=command(['docker','stats','--no-stream','--format','{{json .}}'])
                for line in text.splitlines():
                    r=json.loads(line)
                    if r.get('Name','').startswith('taskforge-'):self.rows.append({'sampledAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),**r})
            except (subprocess.SubprocessError,ValueError):pass
            self.stop.wait(.5)
    def __enter__(self):self.thread.start();return self
    def __exit__(self,*args):self.stop.set();self.thread.join(timeout=5)
def batch(dataset,n,submission_concurrency=4,rate=None,on_submitted=None):
    def submit(i):
        before=time.perf_counter()
        state=request('POST','/jobs',{'datasetId':dataset['datasetId']},'bench-'+str(uuid.uuid4()))
        return {'jobId':state['jobId'],'apiAcknowledgementSeconds':time.perf_counter()-before,'requestedRows':dataset['rows']}
    with Samples() as sample:
        if rate:
            submitted=[];start=time.perf_counter()
            with concurrent.futures.ThreadPoolExecutor(max_workers=submission_concurrency) as pool:
                calls=[]
                for i in range(n):
                    delay=start+i/rate-time.perf_counter()
                    if delay>0:time.sleep(delay)
                    calls.append(pool.submit(submit,i))
                submitted=[f.result() for f in calls]
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=submission_concurrency) as pool:submitted=list(pool.map(submit,range(n)))
        if on_submitted:on_submitted(submitted)
        pending={r['jobId']:r for r in submitted}
        deadline=time.monotonic()+600
        while pending:
            if time.monotonic()>deadline:raise AssertionError('Accepted jobs did not finish')
            for id in list(pending):
                state=request('GET','/jobs/'+id)
                if state['status'] in ('FAILED','CANCELLED'):raise AssertionError('Unexpected terminal state '+json.dumps(state))
                if state['status']=='SUCCEEDED':
                    report=request('GET','/jobs/'+id+'/result')
                    rows=dataset['rows'];expected=rows*(rows+1)//2
                    assert report['rowCount']==rows and report['invalidRows']==0
                    assert report['columns'][0]['numeric']['sum']==expected and report['columns'][1]['numeric']['sum']==expected*2
                    assert report['columns'][0]['numeric']['mean']==(rows+1)/2
                    pending[id]['state']=state
                    pending.pop(id)
            if pending:time.sleep(.05)
    states=[r['state'] for r in submitted]
    first=min(iso(s['createdAt']) for s in states);last=max(iso(s['finishedAt']) for s in states)
    seconds=(last-first).total_seconds()
    return {'jobs':n,'dataset':dataset,'firstCreatedAt':first.isoformat(),'lastFinishedAt':last.isoformat(),'durableBatchSeconds':seconds,
            'jobsPerSecond':n/seconds,'apiAcknowledgementSeconds':summarize([r['apiAcknowledgementSeconds'] for r in submitted]),
            'queueWaitSeconds':summarize([(iso(s['startedAt'])-iso(s['createdAt'])).total_seconds() for s in states]),
            'executionSeconds':summarize([(iso(s['finishedAt'])-iso(s['startedAt'])).total_seconds() for s in states]),
            'completionSeconds':summarize([(iso(s['finishedAt'])-iso(s['createdAt'])).total_seconds() for s in states]),'results':submitted,'resourceSamples':sample.rows}
def configure(workers):
    subprocess.run(COMPOSE+['up','-d','--scale','worker='+str(workers),'--wait','worker'],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
    assert len(command(COMPOSE+['ps','-q','worker']).splitlines())==workers
    wait(lambda:urllib.request.urlopen(BASE+'/actuator/health/readiness',timeout=2).status==200,60)
def metadata():
    docker=command(['docker','info','--format','{{.NCPU}} {{.MemTotal}} {{.ServerVersion}} {{.Architecture}}']).split()
    container=command(COMPOSE+['ps','-q','worker']).splitlines()[0]
    return {'commit':command(['git','rev-parse','HEAD']),'platform':platform.platform(),'docker':{'cpus':int(docker[0]),'memoryBytes':int(docker[1]),'version':docker[2],'architecture':docker[3]},
            'imageId':command(['docker','inspect','--format','{{.Image}}',container]),'workerNanoCpus':command(['docker','inspect','--format','{{.HostConfig.NanoCpus}}',container]),
            'workerMemoryBytes':command(['docker','inspect','--format','{{.HostConfig.Memory}}',container]),'slotsPerWorker':1,'leaseSeconds':6}
def main():
    p=argparse.ArgumentParser();p.add_argument('--workers',default='1,2,4');p.add_argument('--trials',type=int,default=3);p.add_argument('--jobs',type=int,default=18);p.add_argument('--rows',type=int,default=500000);p.add_argument('--label',required=True);p.add_argument('--output',required=True);p.add_argument('--dataset-cache',default='benchmarks/runs/datasets.json')
    a=p.parse_args();cache=ROOT/a.dataset_cache;cache.parent.mkdir(parents=True,exist_ok=True)
    datasets=json.loads(cache.read_text()) if cache.exists() else {}
    if str(a.rows) not in datasets:datasets[str(a.rows)]=upload(a.rows);cache.write_text(json.dumps(datasets,indent=2))
    dataset=datasets[str(a.rows)];out={'label':a.label,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'protocol':'finite batch; uploads/setup/warmup excluded; durable DB timestamps; linear-interpolated percentiles','runs':[]}
    target=ROOT/a.output;target.parent.mkdir(parents=True,exist_ok=True)
    for workers in map(int,a.workers.split(',')):
        configure(workers);out['environment']=metadata()
        batch(dataset,max(2,workers*2)) # explicit warmup excluded from measurements
        for trial in range(1,a.trials+1):
            result=batch(dataset,a.jobs);result.update({'workers':workers,'trial':trial});out['runs'].append(result)
            target.write_text(json.dumps(out,indent=2));print(json.dumps({k:result[k] for k in ['workers','trial','durableBatchSeconds','jobsPerSecond','completionSeconds']}),flush=True)
    out['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();target.write_text(json.dumps(out,indent=2))
if __name__=='__main__':main()
