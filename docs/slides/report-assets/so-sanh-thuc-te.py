import os,json,urllib.request,bisect,datetime,time
os.environ['HF_HUB_OFFLINE']='1'
import pandas as pd,numpy as np
from app.chronos_model import build_context,ensure_ready,ChronosForecaster,NotReady
base='https://aqss-backend.onrender.com/internal/v1/ai/devices/AQSS_ESP32S3_01/'
def fetch(path):
 with urllib.request.urlopen(base+path,timeout=30) as r:return json.load(r)
rows=fetch('recent?minutes=240'); reports=fetch('recent-actuator?minutes=240')
rows.sort(key=lambda r:r['recorded_at']); reports.sort(key=lambda r:r['received_at']); print('rows',len(rows),'reports',len(reports),flush=True)
report_times=[pd.Timestamp(r['received_at']) for r in reports]
for r in rows:
 t=pd.Timestamp(r['recorded_at']); idx=bisect.bisect_right(report_times,t)-1
 if idx>=0 and t-report_times[idx] <= pd.Timedelta(minutes=30): r['fan_level']=reports[idx].get('level')
times=[pd.Timestamp(r['recorded_at']) for r in rows]; samples=[]; prev=None
for i,r in enumerate(rows):
 t=times[i]
 if prev and t-prev < pd.Timedelta(minutes=10):continue
 if i<400:continue
 now=t+pd.Timedelta(seconds=2)
 try:
  context=build_context(rows[max(0,i-800):i+1],now=now,require_fresh=True)
  if ensure_ready(context)!='full':continue
 except NotReady:continue
 actual=[]
 for minutes in (5,15,30):
  target=context.anchor_at+datetime.timedelta(minutes=minutes); k=bisect.bisect_left(times,target); cand=range(max(i+1,k-1),min(len(rows),k+2)); options=[rows[j] for j in cand if not rows[j].get('dust_fault') and not rows[j].get('pm25_saturated') and rows[j].get('pm25') is not None]
  if not options:break
  best=min(options,key=lambda z:abs((pd.Timestamp(z['recorded_at'])-target).total_seconds()))
  if abs((pd.Timestamp(best['recorded_at'])-target).total_seconds())>3:break
  actual.append(float(best['pm25']))
 if len(actual)==3:
  samples.append({'context':context,'actual':actual});prev=t
print('eligible windows',len(samples),flush=True)
if not samples:raise SystemExit('no eligible windows')
results={}
for key,path in [('base','amazon/chronos-2'),('trained','models/chronos-lora/trainer/adapter')]:
 model=ChronosForecaster.load(path,device='cuda'); predictions=[]
 for j,item in enumerate(samples):
  f=model.forecast_context(item['context']);p=f['points'];predictions.append([float(p[k]['pm25']) for k in range(3)])
  if j%5==0:print(key,j,flush=True)
 results[key]=predictions
 del model
 import torch,gc;gc.collect();torch.cuda.empty_cache()
actual=np.array([s['actual'] for s in samples]);metrics={}
for k in results:
 pred=np.array(results[k]);metrics[k]=[round(float(x),2) for x in np.mean(np.abs(pred-actual),axis=0)]
output={'method':'retrospective replay, no new live forecasts; 10-minute spaced origins, 60 min context, nearest future valid measurement within 3 seconds, same windows','device':'AQSS_ESP32S3_01','count':len(samples),'first_origin':samples[0]['context'].anchor_at.isoformat(),'last_origin':samples[-1]['context'].anchor_at.isoformat(),'horizons_minutes':[5,15,30],'mae_ug_m3':metrics,'examples':[{'origin':x['context'].anchor_at.isoformat(),'actual':x['actual'],'base':results['base'][i],'trained':results['trained'][i]} for i,x in enumerate(samples)]}
with open('../docs/slides/report-assets/so-sanh-du-lieu-thuc.json','w') as f:json.dump(output,f,indent=2)
print(json.dumps({k:v for k,v in output.items() if k!='examples'},ensure_ascii=False),flush=True)
