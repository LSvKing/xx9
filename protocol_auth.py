"""Protocol-only guest session bootstrap. Keeps one identity; never rotates on rate limits."""
import base64, fcntl, json, os, time, uuid
from pathlib import Path
from urllib.parse import urlparse
import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad
ROOT = Path(__file__).resolve().parent
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36'

def pace():
    """Share the request budget across local crawler and credential-refresh processes."""
    path = ROOT / '.protocol-request-clock'
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(fd, 'r+') as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        value=f.read().strip()
        try:last=float(value or 0)
        except ValueError:last=0
        interval=max(0,float(os.environ.get('XX9_REQUEST_INTERVAL','15')))
        time.sleep(max(0,min(interval, interval-(time.time()-last))))
        f.seek(0);f.truncate();f.write(str(time.time()));f.flush()

def request(api, frontend, keys, uri, *, method=1, params=None, body=None, access='', jwt=''):
    pace();ts=int(time.time()*1000)
    plain={'method':method,'uri':uri}
    plain['body' if body is not None else 'params']=body if body is not None else params
    encrypted=AES.new(keys[ts%10].encode(),AES.MODE_ECB).encrypt(pad(json.dumps(plain,separators=(',',':')).encode(),16))
    headers={'user-agent':UA,'origin':frontend,'accept':'application/json','content-type':'application/json'}
    if access:headers['accesstoken']=access
    if jwt:headers['jwttoken']=jwt
    r=requests.post(api+'/fast-endecode/main/request',json={'data':base64.b64encode(encrypted).decode(),'time':ts},headers=headers,timeout=30)
    r.raise_for_status();data=r.json()
    if 'data' in data and 'time' in data:
        raw=base64.b64decode(data['data']);hint=int(data['time'])%10
        for i in [hint]+[j for j in range(10) if j!=hint]:
            try:return json.loads(unpad(AES.new(keys[i].encode(),AES.MODE_ECB).decrypt(raw),16))
            except (ValueError,UnicodeError):pass
        raise RuntimeError('API response decode failed')
    return data

def require_ok(data, stage):
    if str(data.get('code'))!='0000':
        raise RuntimeError(f'{stage}: code={data.get("code")} {data.get("message") or data.get("msg") or ""}')
    return data.get('result')

def bootstrap(previous, keys):
    api=previous['api_base'];front=previous['frontend']
    if not api or not front:raise RuntimeError('Missing API/frontend baseline')
    state=ROOT/'.protocol-session.json'
    if state.exists():identity=json.loads(state.read_text())
    else:
        identity={'sign':uuid.uuid4().hex}
        fd=os.open(state,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as f:json.dump(identity,f)
    jwt=require_ok(request(api,front,keys,'app/jwt-token'),'JWT bootstrap')
    account=require_ok(request(api,front,keys,'user/register/free',method=2,jwt=jwt,body={
        'osType':'pc','sign':identity['sign'],'machineCode':'chrome','version':urlparse(front).hostname,'areaCode':'86'
    }),'guest session')
    access=account.get('accessToken')
    if not access:raise RuntimeError('Guest session did not provide accessToken')
    search=request(api,front,keys,'cms/vod/search',method=2,params={'wd':'','page':1,'pageSize':1},access=access,jwt=jwt)
    require_ok(search,'search validation');items=search.get('data') or []
    if not items:raise RuntimeError('Search validation returned no sample')
    detail=request(api,front,keys,'cms/vod/detail/'+str(items[0]['id']),access=access,jwt=jwt)
    if str(detail.get('code')) not in ('0000','FAT20001'):require_ok(detail,'detail validation')
    return dict(previous,access_token=access,jwt_token=jwt)

def save_local(row):
    tmp=ROOT/'.protocol-credentials.json.tmp';path=ROOT/'.protocol-credentials.json'
    fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as f:json.dump(row,f)
    os.replace(tmp,path)
