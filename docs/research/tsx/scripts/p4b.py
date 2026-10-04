import sys,itertools; sys.path.insert(0,'.')
from tsxlib import *
EQ=[s for s in M.sym if M.set_index('sym').loc[s,'type']=='EQUITY' and s not in ('BLX','IIP-UN')]
MD=rebalance_dates('M'); cache={}
def feat(t):
    if t in cache: return cache[t]
    rows=[]
    for s in EQ:
        c=C[s][:t].dropna()
        if len(c)<260: continue
        rows.append(dict(sym=s,sec=SECTOR[s],m3=mom(s,t,3),m6=mom(s,t,6),m12=mom(s,t,12),up=c.iloc[-1]>c.iloc[-200:].mean()))
    cache[t]=pd.DataFrame(rows); return cache[t]
def sched(lb,N,filt,freq):
    out=[]
    for t in rebalance_dates(freq):
        f=feat(t).dropna(subset=[lb])
        if filt: f=f[f.up]
        f=f.sort_values(lb,ascending=False); ps=[];cnt={}
        for _,r in f.iterrows():
            if cnt.get(r.sec,0)>=2: continue
            ps.append(r.sym); cnt[r.sec]=cnt.get(r.sec,0)+1
            if len(ps)==N: break
        w={s:1/N for s in ps}
        if len(ps)<N: w['XSB']=(N-len(ps))/N
        out.append((t,w))
    return out
rows=[]
for lb,N,filt,freq in itertools.product(['m3','m6','m12'],[8,12,16,20],[True,False],['M','Q']):
    v,_,to=simulate(sched(lb,N,filt,freq))
    a=metrics(v,START,SPLIT); b=metrics(v,SPLIT); c=metrics(v)
    rows.append(dict(lb=lb,N=N,filt=filt,freq=freq,to=round(to/10/10000,1),tr_cagr=a['cagr'],tr_dd=a['dd'],tr_sharpe=a['sharpe'],tr_pos12=a['pos12'],te_cagr=b['cagr'],te_dd=b['dd'],te_sharpe=b['sharpe'],te_pos12=b['pos12'],all_cagr=c['cagr'],all_dd=c['dd'],all_worst12=c['worst12'],all_pos12=c['pos12']))
    print(rows[-1],flush=True)
pd.DataFrame(rows).to_pickle('p4b.pkl')
