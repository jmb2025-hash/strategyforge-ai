import sys; sys.path.insert(0,'.')
exec(open('p3.py').read().split("rng=np.random")[0])
rng=np.random.default_rng(11); n=150000; k=len(ASSETS); ix={a:i for i,a in enumerate(ASSETS)}
W=np.zeros((n,k))
for i in range(n):
    m=rng.integers(5,9); idx=rng.choice(k,m,replace=False); w=rng.dirichlet(np.ones(m)); w=np.round(w*20)/20
    if w.sum()==0: continue
    W[i,idx]=w/w.sum()
gold=W[:,ix['CGL-C']]+W[:,ix['XGD']]; tech=W[:,ix['ZQQ']]+W[:,ix['XIT']]; can=W[:,[ix[a] for a in ['XIC','XEI','XEG','XFN','XIT','XGD','XUT','ZLB','CDZ','ZRE']]].sum(axis=1)
ok=(W.max(axis=1)<=0.35)&(gold<=0.25)&(tech<=0.45)&(can>=0.20)&((W>0).sum(axis=1)>=5)
W=W[ok]
c1,d1,v1,w1,p1=evalw(W,START,SPLIT); c2,d2,v2,w2,p2=evalw(W,SPLIT,END+pd.Timedelta(days=1)); c3,d3,v3,w3,p3=evalw(W,START,END+pd.Timedelta(days=1))
df=pd.DataFrame({'tr_cagr':c1*100,'tr_dd':d1*100,'tr_calmar':c1/np.abs(d1),'tr_sharpe':(c1-0.02)/v1,'te_cagr':c2*100,'te_dd':d2*100,'all_cagr':c3*100,'all_dd':d3*100,'all_worst12':w3*100,'all_pos12':p3*100})
df['w']=[' '.join(f"{ASSETS[j]}:{int(round(x*100))}" for j,x in enumerate(r) if x>0) for r in W]
df=df[df.tr_cagr>=10]
pd.set_option('display.width',260); pd.set_option('display.max_colwidth',100)
print(len(df),'constrained portfolios')
print(df.sort_values('tr_calmar',ascending=False).head(12).round(2).to_string(index=False))
top=df[df.tr_calmar>=df.tr_calmar.quantile(0.99)]
print('top 1%: test cagr median',top.te_cagr.median().round(1),'| all cagr',top.all_cagr.median().round(1),'| all dd',top.all_dd.median().round(1))
fr=(top.w.str.extractall(r'([A-Z\-]+):(\d+)').astype({1:int}).groupby(0)[1].agg(['count','mean']))
fr['pct']=fr['count']/len(top)*100; print(fr.sort_values('pct',ascending=False).round(0).head(12))
df.to_pickle('p3b.pkl')
