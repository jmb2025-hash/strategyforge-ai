import pandas as pd, numpy as np, os
H=os.path.dirname(os.path.abspath(__file__))
M=pd.read_csv(f'{H}/meta.csv',parse_dates=['first','last'],keep_default_na=False,na_values={'name':['']})
START=pd.Timestamp('2016-10-03'); SPLIT=pd.Timestamp('2022-01-01'); END=pd.Timestamp('2026-10-02')
def _load():
    C={};A={};D={}
    for s in M.sym:
        d=pd.read_csv(f'{H}/raw/{s}.csv',parse_dates=['date']).set_index('date')
        C[s]=d.close; A[s]=d.adj
        dv=pd.read_csv(f'{H}/raw/{s}.div.csv',parse_dates=['date'])
        D[s]=dv.groupby('date').amount.sum() if len(dv) else pd.Series(dtype=float)
    C=pd.DataFrame(C).sort_index(); A=pd.DataFrame(A).sort_index()
    C=C[C.index>='2014-10-01']; A=A.reindex(C.index)
    Dv=pd.DataFrame({s:v.reindex(C.index,method=None) for s,v in D.items()}).fillna(0.0)
    # dividends whose ex-date falls on a non-trading day: move to the next trading day
    for s,v in D.items():
        miss=v[~v.index.isin(C.index)]
        for dt,amt in miss.items():
            nxt=C.index[C.index>dt]
            if len(nxt): Dv.loc[nxt[0],s]+=amt
    return C,A,Dv
C,A,DV=_load()
SECTOR=dict(zip(M.sym,M.sector))
FULL=[s for s in M.sym if M.set_index('sym').loc[s,'first']<=START]
def last_valid(s): return C[s].last_valid_index()

def simulate(schedule,start=START,end=END,capital=10000.0,drip=True,cost=0.001):
    """schedule: list of (date, {sym: weight}) sorted. Weights sum<=1, rest cash.
    drip=True: dividends buy more of the paying stock; drip=False: dividends paid out (income)."""
    days=C.index[(C.index>=start)&(C.index<=end)]
    px=C.loc[days]; dv=DV.loc[days]
    sched=sorted(schedule,key=lambda x:x[0]); si=0
    sh={}; cash=capital; vals=[]; income=[]; turnover=0.0
    lastpx={}
    for t in days:
        row=px.loc[t]
        # delisted names convert to cash at their last price
        for s in list(sh):
            p=row.get(s)
            if pd.isna(p):
                if t>last_valid(s): cash+=sh.pop(s)*lastpx[s]
            else: lastpx[s]=p
        # dividends (ex-date)
        for s in list(sh):
            a=dv.at[t,s]
            if a>0:
                amt=sh[s]*a
                if drip and not pd.isna(row.get(s)): sh[s]+=amt/row[s]
                else: income.append((t,amt))
        while si<len(sched) and sched[si][0]<=t:
            w=sched[si][1]; si+=1
            V=cash+sum(n*lastpx[s] for s,n in sh.items())
            tgt={s:V*x/row[s] for s,x in w.items() if not pd.isna(row.get(s)) and x>0}
            for s in set(sh)|set(tgt):
                p=row.get(s) if not pd.isna(row.get(s)) else lastpx[s]
                d=tgt.get(s,0.0)-sh.get(s,0.0)
                cash-=d*p+abs(d*p)*cost; turnover+=abs(d*p)
                if tgt.get(s,0)>0: sh[s]=tgt[s]; lastpx[s]=p
                else: sh.pop(s,None)
        vals.append(cash+sum(n*lastpx[s] for s,n in sh.items()))
    v=pd.Series(vals,index=days)
    inc=pd.Series([a for _,a in income],index=[d for d,_ in income],dtype=float)
    return v,inc,turnover

def metrics(v,lo=None,hi=None):
    if lo is not None: v=v[(v.index>=lo)&(v.index<(hi or END+pd.Timedelta(days=1)))]
    v=v/v.iloc[0]; yrs=(v.index[-1]-v.index[0]).days/365.25
    r=v.pct_change().dropna(); m=v.resample('ME').last()
    roll12=(m/m.shift(12)-1).dropna()
    cagr=v.iloc[-1]**(1/yrs)-1; dd=(v/v.cummax()-1).min()
    vol=r.std()*np.sqrt(252); sharpe=(cagr-0.02)/vol if vol>0 else np.nan
    return dict(cagr=round(100*cagr,1),total=round(100*(v.iloc[-1]-1),0),dd=round(100*dd,1),vol=round(100*vol,1),sharpe=round(sharpe,2),
                worst12=round(100*roll12.min(),1) if len(roll12) else np.nan,pos12=round(100*(roll12>0).mean()) if len(roll12) else np.nan,
                calmar=round(cagr/abs(dd),2) if dd<0 else np.nan)

def rebalance_dates(freq,start=START,end=END):
    days=C.index[(C.index>=start)&(C.index<=end)]
    s=pd.Series(days,index=days)
    key={'A':s.index.year,'Q':s.index.year*10+s.index.quarter,'M':s.index.year*100+s.index.month,'S':s.index.year*10+(s.index.month>6)}[freq]
    return list(s.groupby(key).first())

def ttm_div(s,t,years=1):
    d=DV[s]; return d[(d.index>t-pd.DateOffset(years=years))&(d.index<=t)].sum()
def div_features(s,t):
    p=C[s].asof(t)
    if pd.isna(p) or p<=0: return None
    first=C[s].first_valid_index()
    ys=[ttm_div(s,t-pd.DateOffset(years=k)) for k in range(4)]
    # only years fully inside the data count (the data starts in October 2014)
    avail=[y for k,y in enumerate(ys) if t-pd.DateOffset(years=k+1)>=max(first,pd.Timestamp('2014-10-01'))]
    cut=any(avail[k]<0.95*avail[k+1] for k in range(len(avail)-1)) if len(avail)>=2 else False
    n=len(avail)-1
    g3=(avail[0]/avail[n])**(1/n)-1 if n>=1 and avail[n]>0 and avail[0]>0 else np.nan
    return dict(yld=ys[0]/p,g3=g3,cut=cut,paid=ys[0]>0)
def mom(s,t,months):
    a=A[s]; p0=a.asof(t-pd.DateOffset(months=months)); p1=a.asof(t)
    return p1/p0-1 if p0 and not pd.isna(p0) and not pd.isna(p1) else np.nan
def above_sma(s,t,n=200):
    c=C[s][:t].dropna(); return len(c)>=n and c.iloc[-1]>c.iloc[-n:].mean()
