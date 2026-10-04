import sys,itertools; sys.path.insert(0,'.')
from picks import *
def sched2(rank,N,freq,minyld,ov):
    out=[]
    for t in rebalance_dates(freq):
        ps=pick(t,rank,N,2,minyld)
        if not ps: continue
        w={s:1/len(ps) for s in ps}
        if ov=='stock':
            w2={}
            for s,x in w.items():
                k=s if above_sma(s,t) else 'ZST'
                w2[k]=w2.get(k,0)+x
            w=w2
        out.append((t,w))
    return out
def roll12(v,lo,hi):
    m=v.resample('ME').last(); r=(m/m.shift(12)-1).dropna(); r=r[(r.index>=lo)&(r.index<hi)]
    return round(100*r.median(),1),round(100*(r>0).mean())
rows=[]
for rank,N,freq,mny,ov in itertools.product(['yield','yield_nocut','ymom6','ymom3'],[6,8,10,12],['M','Q'],[0.0,0.04,0.05],['none','stock']):
    sc=sched2(rank,N,freq,mny,ov); v,_,to=simulate(sc); vc,inc,_=simulate(sc,drip=False)
    a=metrics(v,START,SPLIT); b=metrics(v,SPLIT); c=metrics(v)
    yl=100*inc.sum()/vc.mean()/10  # avg yearly income / avg value
    trm,trp=roll12(v,START,SPLIT); tem,tep=roll12(v,SPLIT,END+pd.Timedelta(days=1))
    rows.append(dict(rank=rank,N=N,freq=freq,minyld=mny,ov=ov,to=round(to/10/10000,1),yld=round(yl,1),tr_cagr=a['cagr'],tr_dd=a['dd'],tr_sharpe=a['sharpe'],tr_med12=trm,tr_pos12=trp,
                     te_cagr=b['cagr'],te_dd=b['dd'],te_sharpe=b['sharpe'],te_med12=tem,te_pos12=tep,all_cagr=c['cagr'],all_dd=c['dd'],all_worst12=c['worst12']))
    print(rows[-1],flush=True)
save_cache()
R=pd.DataFrame(rows); R.to_pickle('p2.pkl')
