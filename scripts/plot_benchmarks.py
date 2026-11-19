#!/usr/bin/env python3
"""Generate publication-ready artifacts from executed raw data; no synthetic measurement values."""
import datetime,json,statistics
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parents[1]
files={'Baseline':ROOT/'docs/benchmarks/baseline.json','Precompiled regex':ROOT/'docs/benchmarks/optimized.json'}
data={label:json.loads(path.read_text()) for label,path in files.items()}
fig,axes=plt.subplots(1,2,figsize=(10,4.4),layout='constrained')
colors={'Baseline':'#3b6a9a','Precompiled regex':'#21836d'}
summary={}
for label,d in data.items():
    rows=[]
    for workers in [1,2,4]:
        runs=[r for r in d['runs'] if r['workers']==workers]
        assert len(runs)==3 and all(len(r['results'])==18 for r in runs)
        throughput=[r['jobsPerSecond'] for r in runs];p95=[r['completionSeconds']['p95'] for r in runs]
        rows.append({'workers':workers,'trials':3,'verifiedJobs':54,'medianJobsPerSecond':statistics.median(throughput),'minJobsPerSecond':min(throughput),'maxJobsPerSecond':max(throughput),'medianTrialP95Seconds':statistics.median(p95),'medianApiP95Milliseconds':statistics.median(r['apiAcknowledgementSeconds']['p95']*1000 for r in runs)})
    summary[label]=rows
    for ax,key,lo_key,hi_key in [(axes[0],'medianJobsPerSecond','minJobsPerSecond','maxJobsPerSecond'),(axes[1],'medianTrialP95Seconds',None,None)]:
        x=[r['workers'] for r in rows];y=[r[key] for r in rows]
        if lo_key:ax.errorbar(x,y,yerr=[[r[key]-r[lo_key] for r in rows],[r[hi_key]-r[key] for r in rows]],color=colors[label],marker='o',capsize=4,label=label)
        else:ax.plot(x,y,'o-',color=colors[label],label=label)
        for r in d['runs']:
            value=r['jobsPerSecond'] if key=='medianJobsPerSecond' else r['completionSeconds']['p95']
            ax.scatter(r['workers'],value,color=colors[label],s=18,alpha=.45)
for ax in axes:
    ax.set_xticks([1,2,4]);ax.set_xlabel('Independent workers (1 vCPU and 1 slot each)');ax.grid(alpha=.2);ax.set_ylim(bottom=0);ax.spines[['right','top']].set_visible(False)
axes[0].set_ylabel('Completed jobs / second');axes[0].set_title('Finite-batch throughput; median and range')
axes[1].set_ylabel('Seconds');axes[1].set_title('Completion latency; median trial p95')
axes[0].legend(frameon=False)
fig.suptitle('TaskForge: 18 jobs × 500,000 CSV rows, three trials per configuration',fontsize=11)
fig.savefig(ROOT/'docs/benchmarks/worker-scaling.png',dpi=200)
fig.savefig(ROOT/'docs/benchmarks/worker-scaling.svg')
(ROOT/'docs/benchmarks/summary.json').write_text(json.dumps(summary,indent=2))
lines=['# Executed benchmark summary','','Median of three trials. Each trial verifies 18 reports from a 500,000-row, 6,833,355-byte CSV. One vCPU, one slot, 512 MiB per worker.','','| Version | Workers | Median jobs/s | Trial range | Median trial completion p95 | Median API p95 |','| --- | ---: | ---: | ---: | ---: | ---: |']
for label,rows in summary.items():
 for r in rows:lines.append(f"| {label} | {r['workers']} | {r['medianJobsPerSecond']:.3f} | {r['minJobsPerSecond']:.3f}–{r['maxJobsPerSecond']:.3f} | {r['medianTrialP95Seconds']:.3f} s | {r['medianApiP95Milliseconds']:.1f} ms |")
base=summary['Baseline'][0]['medianJobsPerSecond'];optimized=summary['Precompiled regex'][0]['medianJobsPerSecond']
lines+=['',f'Observed one-worker throughput ratio after/before: {optimized/base:.3f}×. Optimized one-to-four-worker ratio: {summary["Precompiled regex"][-1]["medianJobsPerSecond"]/optimized:.3f}×.','', 'These are finite local batches, not sustained production capacity. Points show individual trials; ranges are not confidence intervals. Percentiles are linear interpolation from 18 job samples per trial and have limited tail precision. Uploads, setup and explicit warmup are excluded; durable admission-to-last-completion timestamps define throughput. See BENCHMARKS.md for interpretation and limitations.']
(ROOT/'docs/benchmarks/summary.md').write_text('\n'.join(lines)+'\n')
print('\n'.join(lines))
