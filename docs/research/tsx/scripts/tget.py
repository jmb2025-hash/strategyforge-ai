import json,urllib.request,time,pandas as pd,os,sys
U={
'bank':"RY TD BNS BMO CM NA EQB CWB LB".split(),
'insur':"MFC SLF GWO POW IFC IAG FFH".split(),
'finoth':"BN BAM IGM X ONEX GSY ECN".split(),
'pipe_util':"ENB TRP PPL KEY GEI FTS EMA H AQN CU ACO-X CPX NPI BLX TA ALA SPB INE".split(),
'telecom':"BCE T RCI-B QBR-B CCA".split(),
'energy':"CNQ SU CVE IMO TOU ARX WCP PEY MEG BTE CPG VRN BIR PXT NVA FRU PSK TPZ".split(),
'reit':"REI-UN CAR-UN GRT-UN SRU-UN CHP-UN HR-UN AP-UN DIR-UN BEI-UN CSH-UN NWH-UN FCR-UN CRT-UN IIP-UN MRG-UN SGR-UN".split(),
'indus':"CNR CP WSP TIH WCN TFII CAE STN BYD RBA ATS".split(),
'tech':"SHOP CSU OTEX DSG GIB-A KXS LSPD BB DCBO".split(),
'mater':"NTR ABX AEM FNV WPM TECK-B CCO FM K LUN IVN CS MX WFG CCL-B".split(),
'cons':"ATD L MRU DOL CTC-A QSR MG GIL SAP EMP-A NWC PBH".split(),
'infra':"BIP-UN BEP-UN BEPC NPI".split(),
'etf_eq':"XIU XIC ZCN VCN XEI ZDV VDY CDZ XDV ZWC ZEB XFN XEG XUT ZRE XRE XGD XMA XIT ZWB ZWU XSP ZSP VFV XUS XEF XEM ZQQ QQC VEQT XEQT VGRO XGRO VBAL XBAL ZDY ZLB ZLU HXT".split(),
'etf_fi':"ZAG XBB XSB ZFL ZST XHY ZPR CPD ZHY CBO XTR".split(),
'etf_alt':"CGL-C ZGLD HUG".split(),
}
seen=set(); syms=[]
for k,v in U.items():
    for s in v:
        if s not in seen: seen.add(s); syms.append((k,s))
p1=int(pd.Timestamp('2014-10-01').timestamp()); p2=int(time.time())
meta=[]
for sec,s in syms:
    t=s+'.TO'
    url=f"https://query1.finance.yahoo.com/v8/finance/chart/{t}?period1={p1}&period2={p2}&interval=1d&events=div%2Csplit"
    req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
    try: j=json.load(urllib.request.urlopen(req,timeout=30))
    except Exception as e: print(t,'FAILED',e); continue
    res=j['chart']['result']
    if not res: print(t,'none'); continue
    r=res[0]
    if 'timestamp' not in r: print(t,'no data'); continue
    q=r['indicators']['quote'][0]; adj=r['indicators']['adjclose'][0]['adjclose']
    d=pd.DataFrame({'t':r['timestamp'],'open':q['open'],'high':q['high'],'low':q['low'],'close':q['close'],'volume':q['volume'],'adj':adj}).dropna(subset=['close'])
    d['date']=pd.to_datetime(d.t,unit='s').dt.tz_localize('UTC').dt.tz_convert('America/Toronto').dt.normalize().dt.tz_localize(None)
    d=d.drop_duplicates('date',keep='last').drop(columns='t')
    divs=r.get('events',{}).get('dividends',{}); splits=r.get('events',{}).get('splits',{})
    dv=pd.DataFrame([{'date':pd.to_datetime(v['date'],unit='s').tz_localize('UTC').tz_convert('America/Toronto').normalize().tz_localize(None),'amount':v['amount']} for v in divs.values()]) if divs else pd.DataFrame(columns=['date','amount'])
    sp=pd.DataFrame([{'date':pd.to_datetime(v['date'],unit='s'),'ratio':v['numerator']/v['denominator']} for v in splits.values()]) if splits else pd.DataFrame(columns=['date','ratio'])
    d.to_csv(f'raw/{s}.csv',index=False); dv.to_csv(f'raw/{s}.div.csv',index=False); sp.to_csv(f'raw/{s}.split.csv',index=False)
    m=r['meta']; meta.append(dict(sym=s,sector=sec,name=m.get('longName') or m.get('shortName'),cur=m.get('currency'),type=m.get('instrumentType'),first=d.date.iloc[0].date(),last=d.date.iloc[-1].date(),n=len(d),ndiv=len(dv)))
    time.sleep(0.25)
M=pd.DataFrame(meta); M.to_csv('meta.csv',index=False)
print(len(M),'downloaded'); print(M[M['first']>pd.Timestamp('2016-10-01').date()][['sym','first']].to_string())
