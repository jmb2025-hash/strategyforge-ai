import sys; sys.path.insert(0,'.')
from picks import *
df=pd.read_pickle('p3b.pkl'); top=df[df.tr_calmar>=df.tr_calmar.quantile(0.99)]
ws={}
for w in top.w:
    for kv in w.split():
        a,x=kv.split(':'); ws[a]=ws.get(a,0)+int(x)
ws={a:x/len(top) for a,x in ws.items()}; tot=sum(ws.values())
cons={a:round(x/tot*100) for a,x in sorted(ws.items(),key=lambda z:-z[1]) if x/tot>=0.04}
s=sum(cons.values()); cons={a:x/s for a,x in cons.items()}
print('consensus ETF mix:',{a:round(x*100) for a,x in cons.items()})
qd=rebalance_dates('Q'); ad=set(rebalance_dates('A'))
p1picks={t:pick(t,'ymom6',25,2) for t in rebalance_dates('A')}
def blend(stock_share):
    out=[];cur=None
    for t in qd:
        if t in p1picks: cur=p1picks[t]
        w={a:x*(1-stock_share) for a,x in cons.items()}
        if cur and stock_share>0:
            for s in cur: w[s]=w.get(s,0)+stock_share/len(cur)
        out.append((t,w))
    return out
for sh in (0,0.3,0.5,0.7,1.0):
    v,_,to=simulate(blend(sh))
    a=metrics(v,START,SPLIT); b=metrics(v,SPLIT); c=metrics(v)
    print(f"stocks {int(sh*100)}%: train {a['cagr']}%/dd{a['dd']} | test {b['cagr']}%/dd{b['dd']} | all {c['cagr']}%/yr dd{c['dd']} worst12 {c['worst12']} pos12 {c['pos12']} sharpe {c['sharpe']}")
import json; json.dump(cons,open('cons.json','w'))
