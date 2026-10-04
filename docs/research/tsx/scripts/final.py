import sys,json; sys.path.insert(0,'.')
from picks import *
import picks as PK
src4=open('p4.py').read().split('\nrows=[]')[0]; exec(src4)
srcb=open('p4b.py').read().split('\nrows=[]')[0]; exec(srcb)
cons=json.load(open('cons.json'))
QD=rebalance_dates('Q'); ADs=rebalance_dates('A'); MDs=rebalance_dates('M')
# Plan 1: no-cut dividend payers, yield + 6m momentum, top 25, <=2/sector, annual
P1=PK.sched('ymom6',25,2,'A')
# Plan 2: no-cut payers, yield>=4%, top 12 by yield, <=2/sector, monthly, each holding above its 200-day average else ZST
P2=[]
for t in MDs:
    ps=PK.pick(t,'yield_nocut',12,2,0.04); w={}
    for s in ps: k=s if above_sma(s,t) else 'ZST'; w[k]=w.get(k,0)+1/len(ps)
    P2.append((t,w))
# Plan 3: consensus ETF mix + Plan 1 stocks, quarterly
p1d=dict(P1)
def p3(sh):
    out=[];cur=None
    for t in QD:
        if t in p1d: cur=list(p1d[t])
        w={a:x*(1-sh) for a,x in cons.items()}
        if cur and sh>0:
            for s in cur: w[s]=w.get(s,0)+sh/len(cur)
        out.append((t,w))
    return out
best=None
for sh in (0,0.3,0.5,0.7):
    v,_,_=simulate(p3(sh)); a=metrics(v,START,SPLIT); print('P3 stocks',sh,'train sharpe',a['sharpe'],'calmar',a['calmar'],flush=True)
    if best is None or a['sharpe']>best[1]: best=(sh,a['sharpe'])
P3=p3(best[0]); print('P3 chosen stock share',best[0])
# Plan 4: 50% ETF rotation (top 5 of 17 by 3m, monthly) + 50% top 16 stocks by 6m momentum (<=2/sector, quarterly)
etf=dict(sched4(3,5,'none')); stk=dict(sched('m6',16,False,'Q'))
P4=[];cur={}
for t in MD:
    if t in stk: cur=stk[t]
    w={a:x*0.5 for a,x in etf[t].items()}
    for s,x in cur.items(): w[s]=w.get(s,0)+x*0.5
    P4.append((t,w))
PLANS={'P1':(P1,0.001),'P2':(P2,0.001),'P3':(P3,0.001),'P4':(P4,0.0007)}
out={}
for k,(sc,cost) in PLANS.items():
    vd,_,to=simulate(sc,cost=cost); vc,inc,_=simulate(sc,drip=False,cost=cost)
    mo=pd.DataFrame({'value_drip':vd.resample('ME').last(),'value_cash':vc.resample('ME').last(),'dividends':inc.resample('ME').sum()}).fillna({'dividends':0})
    yrs=vd.groupby(vd.index.year).apply(lambda x:x.iloc[-1]/x.iloc[0]-1)
    out[k]=dict(drip=vd,cash=vc,income=inc,monthly=mo,turnover=to/10/10000,
               m_all=metrics(vd),m_tr=metrics(vd,START,SPLIT),m_te=metrics(vd,SPLIT),last=sc[-1],years=yrs)
    print(k,out[k]['m_all'],'train',out[k]['m_tr']['cagr'],'test',out[k]['m_te']['cagr'],'| drip end',round(vd.iloc[-1]),'| cash end',round(vc.iloc[-1]),'+ income',round(inc.sum()),'| last12 income',round(inc[inc.index>END-pd.DateOffset(years=1)].sum()),flush=True)
for b in ['XIC','VDY','XEI','VFV']:
    vd,_,_=simulate([(START,{b:1.0})]); vc,inc,_=simulate([(START,{b:1.0})],drip=False)
    out[b]=dict(drip=vd,cash=vc,income=inc,m_all=metrics(vd),m_tr=metrics(vd,START,SPLIT),m_te=metrics(vd,SPLIT),years=vd.groupby(vd.index.year).apply(lambda x:x.iloc[-1]/x.iloc[0]-1),
                monthly=pd.DataFrame({'value_drip':vd.resample('ME').last(),'value_cash':vc.resample('ME').last(),'dividends':inc.resample('ME').sum()}).fillna({'dividends':0}))
pd.to_pickle(out,'final.pkl'); PK.save_cache()
for k in ('P1','P2','P3','P4'):
    d,w=out[k]['last']; print(k,'holdings from',d.date(),':',', '.join(f"{s} {x*100:.1f}%" for s,x in sorted(w.items(),key=lambda z:-z[1])))
