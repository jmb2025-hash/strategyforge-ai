import pickle,os
from tsxlib import *
STOCKS=[s for s in M.sym if M.set_index('sym').loc[s,'type']=='EQUITY' and s not in ('BLX','IIP-UN')]
_fc=f'{H}/feat_cache.pkl'
feat_cache=pickle.load(open(_fc,'rb')) if os.path.exists(_fc) else {}
def save_cache(): pickle.dump(feat_cache,open(_fc,'wb'))
def feats(t):
    if t in feat_cache: return feat_cache[t]
    rows=[]
    for s in STOCKS:
        if C[s][:t].dropna().shape[0]<260: continue
        f=div_features(s,t)
        if not f or not f['paid']: continue
        f['sym']=s; f['sec']=SECTOR[s]; f['m12']=mom(s,t,12); f['m6']=mom(s,t,6); f['m3']=mom(s,t,3); rows.append(f)
    df=pd.DataFrame(rows); feat_cache[t]=df; return df
def pick(t,rank,N,cap,minyld=0.0):
    f=feats(t).copy()
    if 'm6' not in f: f['m6']=[mom(s,t,6) for s in f.sym]; f['m3']=[mom(s,t,3) for s in f.sym]
    f=f[f.yld>=minyld]
    if rank in ('yield_nocut','yg','grow','ymom','ymom6','ymom3'): f=f[~f.cut]
    if rank in ('yield','yield_nocut'): f=f.sort_values('yld',ascending=False)
    elif rank=='yg': f['sc']=f.yld.rank(pct=True)+f.g3.fillna(-1).rank(pct=True); f=f.sort_values('sc',ascending=False)
    elif rank=='grow': f=f[f.yld>=0.03].sort_values('g3',ascending=False)
    elif rank in ('ymom','ymom6','ymom3'):
        k={'ymom':'m12','ymom6':'m6','ymom3':'m3'}[rank]
        f['sc']=f.yld.rank(pct=True)+f[k].fillna(-1).rank(pct=True); f=f.sort_values('sc',ascending=False)
    out=[];cnt={}
    for _,r in f.iterrows():
        if cap and cnt.get(r.sec,0)>=cap: continue
        out.append(r.sym); cnt[r.sec]=cnt.get(r.sec,0)+1
        if len(out)==N: break
    return out
def sched(rank,N,cap,freq,start=START,end=END,minyld=0.0):
    out=[]
    for t in rebalance_dates(freq,start,end):
        ps=pick(t,rank,N,cap,minyld)
        if ps: out.append((t,{s:1/len(ps) for s in ps}))
    return out
