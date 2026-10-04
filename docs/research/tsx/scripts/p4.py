import sys,itertools; sys.path.insert(0,'.')
from tsxlib import *
U4=['XIU','XIT','XFN','XEG','XMA','XGD','XUT','ZRE','VFV','ZQQ','XEF','XEM','CGL-C','ZAG','XSB','ZLB','XEI']
SAFE='XSB'
MD=rebalance_dates('M')
def score(s,t,lb):
    if lb=='blend': 
        ms=[mom(s,t,m) for m in (1,3,6,12)]
        return np.nan if any(pd.isna(x) for x in ms) else np.mean(ms)
    return mom(s,t,lb)
def sched4(lb,k,absf,univ=U4):
    out=[]
    for t in MD:
        sc={s:score(s,t,lb) for s in univ if s!=SAFE}
        sc={s:x for s,x in sc.items() if not pd.isna(x)}
        top=sorted(sc,key=lambda s:-sc[s])[:k]
        w={}
        for s in top:
            ok=True
            if absf=='abs': ok=sc[s]>0
            elif absf=='rel': ok=sc[s]>(score(SAFE,t,lb) or 0)
            w[s if ok else SAFE]=w.get(s if ok else SAFE,0)+1/k
        out.append((t,w))
    return out
def roll12(v,lo,hi):
    m=v.resample('ME').last(); r=(m/m.shift(12)-1).dropna(); r=r[(r.index>=lo)&(r.index<hi)]
    return round(100*r.median(),1),round(100*(r>0).mean()),round(100*r.min(),1)
rows=[]
for lb,k,absf in itertools.product([1,3,6,12,'blend'],[1,2,3,4,5],['none','abs','rel']):
    v,_,to=simulate(sched4(lb,k,absf),cost=0.0005)
    a=metrics(v,START,SPLIT); b=metrics(v,SPLIT); c=metrics(v)
    trm=roll12(v,START,SPLIT); tem=roll12(v,SPLIT,END+pd.Timedelta(days=1))
    rows.append(dict(lb=lb,k=k,f=absf,to=round(to/10/10000,1),tr_cagr=a['cagr'],tr_dd=a['dd'],tr_sharpe=a['sharpe'],tr_pos12=trm[1],tr_min12=trm[2],te_cagr=b['cagr'],te_dd=b['dd'],te_sharpe=b['sharpe'],te_pos12=tem[1],te_min12=tem[2],all_cagr=c['cagr'],all_dd=c['dd'],all_worst12=c['worst12'],all_pos12=c['pos12']))
R=pd.DataFrame(rows); R.to_pickle('p4.pkl')
pd.set_option('display.width',260)
print(R.sort_values('tr_sharpe',ascending=False).head(15).to_string(index=False))
