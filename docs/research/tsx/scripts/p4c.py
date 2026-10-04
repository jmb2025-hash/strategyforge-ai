import sys,io,contextlib; sys.path.insert(0,'.')
from tsxlib import *
src4=open('p4.py').read().split('\nrows=[]')[0]; exec(src4)
srcb=open('p4b.py').read().split('\nrows=[]')[0]; exec(srcb)
etf=dict(sched4(3,5,'none')); stk=dict(sched('m6',16,False,'Q'))
def blend(se):
    out=[];cur={}
    for t in MD:
        if t in stk: cur=stk[t]
        w={a:x*(1-se) for a,x in etf[t].items()}
        for s,x in cur.items(): w[s]=w.get(s,0)+x*se
        out.append((t,w))
    return out
for se in (0,0.25,0.5,0.75,1.0):
    v,_,to=simulate(blend(se),cost=0.0007)
    a=metrics(v,START,SPLIT); b=metrics(v,SPLIT); c=metrics(v)
    print(f"stocks {int(se*100)}%: train {a['cagr']}% dd{a['dd']} sharpe {a['sharpe']} pos12 {a['pos12']} | test {b['cagr']}% dd{b['dd']} sharpe {b['sharpe']} pos12 {b['pos12']} | all {c['cagr']}% dd{c['dd']} worst12 {c['worst12']} pos12 {c['pos12']}",flush=True)
