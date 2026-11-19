#!/usr/bin/env python3
"""Real-workload scenarios and scoped process failures against the local TaskForge stack."""
import datetime,json,subprocess,time
from pathlib import Path
import benchmark as b
ROOT=b.ROOT
cache=ROOT/'benchmarks/runs/datasets.json'
datasets=json.loads(cache.read_text()) if cache.exists() else {}
def dataset(rows):
    if str(rows) not in datasets:
        datasets[str(rows)]=b.upload(rows);cache.write_text(json.dumps(datasets,indent=2))
    return datasets[str(rows)]
def sql(query):return b.command(b.COMPOSE+['exec','-T','postgres','psql','-U','taskforge','-d','taskforge','-Atc',query])
def ids_clause(submitted):return ','.join("'"+str(__import__('uuid').UUID(r['jobId']))+"'" for r in submitted)
def recover_container(service):
    subprocess.run(b.COMPOSE+['up','-d','--scale','worker=4','--wait',service],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
def worker_crash(submitted):
    active=b.wait(lambda:sql("select a.worker_id from job_attempts a join jobs j on j.id=a.job_id and j.generation=a.generation where j.status='RUNNING' and j.id in ("+ids_clause(submitted)+") limit 1"),10)
    prefix=active.rsplit('-',1)[0]
    for container in b.command(b.COMPOSE+['ps','-q','worker']).splitlines():
        logs=b.command(['docker','logs','--tail','100',container])
        if prefix in logs:
            subprocess.run(['docker','kill','--signal','KILL',container],check=True,stdout=subprocess.DEVNULL)
            faults.append({'kind':'active_worker_SIGKILL','workerId':active,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()})
            killed.append(container);return
    raise AssertionError('Could not identify the active worker container')
def outage(service,seconds):
    def inject(submitted):
        container=b.command(b.COMPOSE+['ps','-q',service]).strip()
        subprocess.run(['docker','stop','--time','0',container],check=True,stdout=subprocess.DEVNULL)
        faults.append({'kind':service+'_outage','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'seconds':seconds})
        try:time.sleep(seconds) # controlled outage, not a fake processing workload
        finally:subprocess.run(['docker','start',container],check=True,stdout=subprocess.DEVNULL)
        b.wait(lambda:__import__('urllib.request').request.urlopen(b.BASE+'/actuator/health/readiness',timeout=2).status==200,30)
    return inject
faults=[];killed=[];out={'protocol':'functional load/failure scenarios; not used as controlled scaling comparisons','scenarios':[]}
target=ROOT/'docs/benchmarks/scenarios.json'
def save(name,result):
    result['name']=name;out['scenarios'].append(result);out['faults']=faults;target.write_text(json.dumps(out,indent=2));print(name,result['jobs'],round(result['durableBatchSeconds'],3),flush=True)
def main():
    b.configure(4);out['environment']=b.metadata()
    small=dataset(10000);medium=dataset(250000);large=dataset(1000000)
    save('small_low_load',b.batch(small,8))
    save('large_csv',b.batch(large,8))
    # Interleave differently sized jobs in one admission burst, then verify every report.
    with b.Samples() as samples:
        submitted=[]
        for i in range(12):
            data=[small,medium,large][i%3]
            state=b.request('POST','/jobs',{'datasetId':data['datasetId']},'mixed-'+str(__import__('uuid').uuid4()))
            submitted.append({'jobId':state['jobId'],'requestedRows':data['rows']})
        for row in submitted:
            state=b.wait(lambda:b.request('GET','/jobs/'+row['jobId']) if b.request('GET','/jobs/'+row['jobId'])['status'] in ['SUCCEEDED','FAILED','CANCELLED'] else None)
            assert state['status']=='SUCCEEDED';row['state']=state
            report=b.request('GET','/jobs/'+row['jobId']+'/result');n=row['requestedRows'];assert report['rowCount']==n and report['columns'][0]['numeric']['sum']==n*(n+1)//2
    seconds=(max(b.iso(r['state']['finishedAt']) for r in submitted)-min(b.iso(r['state']['createdAt']) for r in submitted)).total_seconds()
    save('mixed_sizes',{'jobs':12,'durableBatchSeconds':seconds,'results':submitted,'resourceSamples':samples.rows})
    save('burst_100',b.batch(small,100,submission_concurrency=8))
    save('sustained_20_per_second',b.batch(medium,200,submission_concurrency=8,rate=20))
    try:
        save('worker_crash',b.batch(large,12,on_submitted=worker_crash))
    finally:
        for container in killed:subprocess.run(['docker','start',container],check=True,stdout=subprocess.DEVNULL)
        recover_container('worker')
    save('redis_interruption',b.batch(medium,16,on_submitted=outage('redis',3)))
    save('database_interruption',b.batch(large,12,on_submitted=outage('postgres',3)))
    for scenario in out['scenarios']:
        ids=ids_clause(scenario['results'])
        rows=sql("select count(*),count(*) filter(where generation>1),count(*) filter(where outcome is not null and outcome<>'SUCCEEDED') from job_attempts where job_id in ("+ids+")").split('|')
        scenario['attemptSummary']={'attempts':int(rows[0]),'additionalAttempts':int(rows[1]),'nonSuccessAttemptOutcomes':int(rows[2])}
    out['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();target.write_text(json.dumps(out,indent=2))
if __name__=='__main__':main()
