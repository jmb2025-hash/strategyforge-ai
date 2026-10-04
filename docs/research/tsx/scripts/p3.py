import sys; sys.path.insert(0,'.')
from tsxlib import *
ASSETS=['XIC','XEI','VFV','ZQQ','XEF','XEM','ZAG','XSB','CGL-C','ZRE','XEG','XFN','XIT','XGD','XUT','ZLB','CDZ']
TR=A[ASSETS].loc[START-pd.Timedelta(days=40):END].resample('ME').last().pct_change().dropna()
TR=TR[TR.index>START]
def evalw(W,lo,hi):
    R=TR[(TR.index>=lo)&(TR.index<hi)].values   # months x assets
    pr=R@W.T                                     # months x portfolios (monthly rebalance)
    eq=np.cumprod(1+pr,axis=0); yrs=len(R)/12
    cagr=eq[-1]**(1/yrs)-1; dd=(eq/np.maximum.accumulate(eq,axis=0)-1).min(axis=0)
    vol=pr.std(axis=0)*np.sqrt(12)
    r12=eq[12:]/eq[:-12]-1
    return cagr,dd,vol,r12.min(axis=0),(r12>0).mean(axis=0)
rng=np.random.default_rng(7); n=60000; k=len(ASSETS)
W=np.zeros((n,k))
for i in range(n):
    m=rng.integers(3,8); idx=rng.choice(k,m,replace=False); w=rng.dirichlet(np.ones(m)); 
    w=np.round(w*20)/20
    if w.sum()==0: continue
    W[i,idx]=w/w.sum()
W=W[(W.max(axis=1)<=0.5)]
c1,d1,v1,w1,p1=evalw(W,START,SPLIT); c2,d2,v2,w2,p2=evalw(W,SPLIT,END+pd.Timedelta(days=1)); c3,d3,v3,w3,p3=evalw(W,START,END+pd.Timedelta(days=1))
df=pd.DataFrame({'tr_cagr':c1*100,'tr_dd':d1*100,'tr_calmar':c1/np.abs(d1),'tr_sharpe':(c1-0.02)/v1,'te_cagr':c2*100,'te_dd':d2*100,'all_cagr':c3*100,'all_dd':d3*100,'all_worst12':w3*100,'all_pos12':p3*100})
df['w']=[' '.join(f"{ASSETS[j]}:{int(round(x*100))}" for j,x in enumerate(r) if x>0) for r in W]
pd.set_option('display.width',260); pd.set_option('display.max_colwidth',90)
print(len(df),'portfolios')
for crit in ['tr_calmar','tr_sharpe']:
    print('== top by',crit); print(df.sort_values(crit,ascending=False).head(10).round(2).to_string(index=False))
# consistency of the top decile by train calmar
top=df[df.tr_calmar>=df.tr_calmar.quantile(0.99)]
print('top 1% by train calmar -> test cagr median',top.te_cagr.median().round(1),'all dd median',top.all_dd.median().round(1))
print('asset frequency in top 1%:',(top.w.str.extractall(r'([A-Z\-]+):')[0].value_counts()/len(top)*100).round(0).head(12).to_dict())
df.to_pickle('p3.pkl')
