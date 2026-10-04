import sys,itertools; sys.path.insert(0,'.')
from picks import *
rows=[]
for rank,N,freq in itertools.product(['yield','yield_nocut','yg','grow','ymom','ymom6'],[10,15,20,25,30],['A','S','Q']):
    v,_,to=simulate(sched(rank,N,2,freq))
    a=metrics(v,START,SPLIT); b=metrics(v,SPLIT); c=metrics(v)
    rows.append(dict(rank=rank,N=N,freq=freq,to=round(to/10/10000,1),**{f'tr_{k}':x for k,x in a.items()},**{f'te_{k}':x for k,x in b.items()},**{f'all_{k}':x for k,x in c.items()}))
save_cache()
R=pd.DataFrame(rows); R.to_pickle('p1d.pkl')
pd.set_option('display.width',250)
cols=['rank','N','freq','to','tr_cagr','tr_dd','tr_sharpe','tr_calmar','te_cagr','te_dd','te_sharpe','all_cagr','all_dd','all_worst12','all_pos12']
print(R.sort_values('tr_calmar',ascending=False)[cols].head(15).to_string(index=False))
print(R.groupby('rank')[['tr_cagr','tr_calmar','te_cagr','te_sharpe','all_cagr','all_worst12']].median())
