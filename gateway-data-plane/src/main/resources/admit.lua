-- All keys share a tenant hash tag. One atomic admission counts every admitted request.
local now=redis.call('TIME'); local ms=now[1]*1000+math.floor(now[2]/1000)
local rate=tonumber(ARGV[1]);local cap=tonumber(ARGV[2]);local daily=tonumber(ARGV[3]);local monthly=tonumber(ARGV[4])
local day=math.floor(ms/86400000);day=math.max(day,tonumber(redis.call('HGET',KEYS[2],'day') or day));local z=day+719468;local era=math.floor(z/146097);local doe=z-era*146097
local yoe=math.floor((doe-math.floor(doe/1460)+math.floor(doe/36524)-math.floor(doe/146096))/365)
local y=yoe+era*400;local doy=doe-(365*yoe+math.floor(yoe/4)-math.floor(yoe/100))
local mp=math.floor((5*doy+2)/153);if mp>=10 then y=y+1 end;local month=tostring(y)..'-'..tostring(mp+(mp<10 and 3 or -9))
local oldDay=redis.call('HGET',KEYS[2],'day');local oldMonth=redis.call('HGET',KEYS[2],'month')
local d=0;local m=0
if oldDay==tostring(day) then d=tonumber(redis.call('HGET',KEYS[2],'d') or '0') end
if oldMonth==month then m=tonumber(redis.call('HGET',KEYS[2],'m') or '0') end
local t=tonumber(redis.call('HGET',KEYS[1],'tokens') or cap)
local last=tonumber(redis.call('HGET',KEYS[1],'ts') or ms)
t=math.min(cap,t+math.max(0,ms-last)*rate/1000)
if d>=daily or m>=monthly then return {0,86400} end
if t<1 then return {0,math.ceil((1-t)/rate)} end
redis.call('HSET',KEYS[1],'tokens',t-1,'ts',ms);redis.call('EXPIRE',KEYS[1],math.ceil(cap/rate)+60)
redis.call('HINCRBY',KEYS[2],'revision',1)
redis.call('HSET',KEYS[2],'day',day,'month',month,'d',d+1,'m',m+1);redis.call('EXPIRE',KEYS[2],5356800)
return {1,0}
